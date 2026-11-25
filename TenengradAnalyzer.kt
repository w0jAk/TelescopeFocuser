package com.example.focussetter

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

class TenengradAnalyzer(
    private val onSharpness: (Double) -> Unit
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            val width = image.width
            val height = image.height


            val yBytes = buffer.toByteArray()

            fun yAt(x: Int, y: Int): Int {
                val idx = y * rowStride + x * pixelStride
                return yBytes[idx].toInt() and 0xFF
            }

            var sum = 0.0
            var count = 0

            for (j in 1 until height - 1) {
                for (i in 1 until width - 1) {
                    val a00 = yAt(i - 1, j - 1)
                    val a01 = yAt(i,     j - 1)
                    val a02 = yAt(i + 1, j - 1)

                    val a10 = yAt(i - 1, j)
                    val a12 = yAt(i + 1, j)

                    val a20 = yAt(i - 1, j + 1)
                    val a21 = yAt(i,     j + 1)
                    val a22 = yAt(i + 1, j + 1)

                    val gx = (-a00 + a02) + (-2 * a10 + 2 * a12) + (-a20 + a22)
                    val gy = (-a00 - 2 * a01 - a02) + (a20 + 2 * a21 + a22)

                    val g2 = (gx * gx + gy * gy).toDouble()
                    sum += g2
                    count++
                }
            }

            val tenengrad = if (count > 0) sum / count else 0.0
            onSharpness(tenengrad)

        } catch (_: Throwable) {

        } finally {
            image.close()
        }
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val out = ByteArray(remaining())
        get(out)
        return out
    }
}
