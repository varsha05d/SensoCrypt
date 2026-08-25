package com.sensocrypt.capture

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/** plan.md §4.5: frames go over the wire as JPEG q40. Replicates gray into R=G=B --
 * JPEG's chroma subsampling compresses the (constant, redundant) color channels away
 * cheaply, so this costs little bandwidth over a true single-channel encoder. */
fun grayToJpeg(frame: GrayFrame, quality: Int = 40): ByteArray {
    val pixels = IntArray(frame.width * frame.height)
    for (i in pixels.indices) {
        val g = frame.pixels[i].toInt() and 0xFF
        pixels[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }
    val bitmap = Bitmap.createBitmap(pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
    bitmap.recycle()
    return out.toByteArray()
}
