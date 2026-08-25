package com.sensocrypt.net

/**
 * Backend location. Swap this one block when moving between a local dev backend and the
 * deployed one -- everything else (AuthApi, SessionApi, SignalSocket, TelemetrySocket)
 * builds its URLs from these two constants alone.
 *
 * Local dev (same WiFi as the Mac running docker-compose): use the Mac's LAN IP, not
 * 127.0.0.1, so a phone can reach it (check with `ipconfig getifaddr en0`), and http/ws
 * since there's no TLS locally. This also needs a cleartext exception in
 * res/xml/network_security_config.xml for that IP.
 *
 * Deployed (Render, or any real host): use its HTTPS domain and https/wss -- no
 * network_security_config cleartext exception needed, and it works from any network, not
 * just the same LAN.
 */
const val BACKEND_HOST = "192.168.1.2:8000"
const val BACKEND_HTTP_SCHEME = "http"
const val BACKEND_WS_SCHEME = "ws"

// Once deployed to Render, replace the three lines above with:
// const val BACKEND_HOST = "sensocrypt-backend.onrender.com"
// const val BACKEND_HTTP_SCHEME = "https"
// const val BACKEND_WS_SCHEME = "wss"
