package com.sensocrypt.call

import android.content.Context
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import org.webrtc.DataChannel

private open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String?) = Unit
    override fun onSetFailure(error: String?) = Unit
}

/**
 * Thin wrapper around the WebRTC peer connection for a 1:1 video call (plan.md §11 Phase 6).
 * Uses WebRTC's own camera capturer, deliberately separate from the CameraX pipeline that
 * feeds the liveness engine -- both wanting exclusive camera access at once is a real
 * constraint (see plan.md notes on this), so calling and liveness-checking are sequential,
 * not simultaneous: the call runs on its own camera session, and "Check Liveness" briefly
 * uses CameraX's session instead.
 */
class WebRtcSession(private val context: Context, private val eglBase: EglBase) {
    private val factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null

    var onIceCandidate: ((IceCandidate) -> Unit)? = null
    var onRemoteVideoTrack: ((VideoTrack) -> Unit)? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
        )
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun start(localRenderer: SurfaceViewRenderer, livenessFrameSink: VideoSink? = null) {
        localRenderer.init(eglBase.eglBaseContext, null)

        val capturer = createFrontCameraCapturer() ?: throw IllegalStateException("No front camera available")
        videoCapturer = capturer

        val surfaceTextureHelper = SurfaceTextureHelper.create("WebRtcCapture", eglBase.eglBaseContext)
        val videoSource = factory.createVideoSource(false)
        capturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
        capturer.startCapture(640, 480, 30)

        val videoTrack = factory.createVideoTrack("video0", videoSource)
        videoTrack.addSink(localRenderer)
        // Feeds the SAME liveness pipeline the standalone verify flow uses (see
        // WebRtcFrameSink) -- avoids a second camera session fighting this one for access.
        livenessFrameSink?.let { videoTrack.addSink(it) }

        val audioSource: AudioSource = factory.createAudioSource(MediaConstraints())
        val audioTrack: AudioTrack = factory.createAudioTrack("audio0", audioSource)

        // STUN alone only works when at least one side's NAT allows a direct hole-punch --
        // fine on the same LAN, but two phones on two different home/mobile networks often
        // sit behind NATs that block that entirely. TURN relays the media through a third
        // party in that case. Open Relay Project's free static credentials (no signup, no
        // cost) stand in for a self-hosted coturn server, which isn't worth running for a
        // free-tier deployment.
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                    .setUsername("openrelayproject")
                    .setPassword("openrelayproject")
                    .createIceServer(),
                PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                    .setUsername("openrelayproject")
                    .setPassword("openrelayproject")
                    .createIceServer(),
                PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                    .setUsername("openrelayproject")
                    .setPassword("openrelayproject")
                    .createIceServer(),
            ),
        )
        peerConnection = factory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    onIceCandidate?.invoke(candidate)
                }

                override fun onTrack(transceiver: RtpTransceiver?) {
                    val track = transceiver?.receiver?.track()
                    if (track is VideoTrack) onRemoteVideoTrack?.invoke(track)
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
                override fun onAddStream(stream: MediaStream?) = Unit
                override fun onRemoveStream(stream: MediaStream?) = Unit
                override fun onDataChannel(dc: DataChannel?) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
            },
        )

        peerConnection?.addTrack(videoTrack, listOf("stream0"))
        peerConnection?.addTrack(audioTrack, listOf("stream0"))
    }

    fun createOffer(onCreated: (SessionDescription) -> Unit) {
        peerConnection?.createOffer(
            object : SdpObserverAdapter() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc == null) return
                    peerConnection?.setLocalDescription(SdpObserverAdapter(), desc)
                    onCreated(desc)
                }
            },
            MediaConstraints(),
        )
    }

    fun createAnswer(onCreated: (SessionDescription) -> Unit) {
        peerConnection?.createAnswer(
            object : SdpObserverAdapter() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc == null) return
                    peerConnection?.setLocalDescription(SdpObserverAdapter(), desc)
                    onCreated(desc)
                }
            },
            MediaConstraints(),
        )
    }

    fun setRemoteDescription(desc: SessionDescription) {
        peerConnection?.setRemoteDescription(SdpObserverAdapter(), desc)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun close() {
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        peerConnection?.close()
        peerConnection?.dispose()
    }

    private fun createFrontCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        for (name in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(name)) {
                return enumerator.createCapturer(name, null)
            }
        }
        return null
    }
}
