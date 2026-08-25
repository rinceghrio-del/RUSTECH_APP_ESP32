package com.example.facerobot.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Mga tulong-function para sa pag-convert ng CameraX ImageProxy (YUV_420_888) papuntang
 * Bitmap, at pag-crop/resize/rotate nito. Ginagamit ito ng YOLO detector at ng face
 * embedder dahil pareho silang nangangailangan ng plain Bitmap bago i-feed sa TFLite.
 */
object ImageUtils {

    /**
     * Kino-combine ang Y, U, V planes ng ImageProxy papunta sa isang NV21 byte array,
     * tapos ico-compress bilang JPEG at ide-decode pabalik bilang Bitmap.
     * Medyo may overhead ito (JPEG encode/decode) pero simple at gumagana sa halos
     * lahat ng device kumpara sa manual pixel math.
     */
    fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val nv21 = yuv420ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
        val jpegBytes = out.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

        val rotationDegrees = image.imageInfo.rotationDegrees
        return if (rotationDegrees != 0) {
            rotateBitmap(bitmap, rotationDegrees)
        } else {
            bitmap
        }
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val ySize = yPlane.buffer.remaining()
        val uSize = uPlane.buffer.remaining()
        val vSize = vPlane.buffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yPlane.buffer.get(nv21, 0, ySize)

        val vBuffer = vPlane.buffer
        val uBuffer = uPlane.buffer
        // NV21 expects V then U interleaved. Karamihan ng devices ay may pixelStride
        // na 2 sa U/V planes (semi-planar), kaya ganito ang pag-interleave.
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uPixelStride = uPlane.pixelStride

        if (vPixelStride == 2 && uPixelStride == 2) {
            // Karaniwang layout: pwede nating direktang kopyahin ang V plane bytes
            // (naka-interleave na ang VUVU dahil magkadikit sila sa memory sa maraming device).
            var pos = ySize
            var vPos = 0
            for (row in 0 until image.height / 2) {
                for (col in 0 until image.width / 2) {
                    val vIndex = row * vRowStride + col * vPixelStride
                    if (vIndex < vBuffer.remaining() && pos + 1 < nv21.size) {
                        nv21[pos] = vBuffer.get(vIndex)
                        val uIndex = row * uPlane.rowStride + col * uPixelStride
                        nv21[pos + 1] = if (uIndex < uBuffer.remaining()) uBuffer.get(uIndex) else 0
                        pos += 2
                    }
                }
            }
        } else {
            // Fallback: buo-buong kopyahin (baka hindi perfect ang color pero hindi
            // babagsak ang app)
            vBuffer.get(nv21, ySize, min(vSize, nv21.size - ySize))
        }

        return nv21
    }

    fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** I-crop ang bitmap gamit ang Rect, pero i-clamp muna sa loob ng bounds ng bitmap. */
    fun safeCrop(bitmap: Bitmap, rect: Rect, marginPercent: Float = 0.25f): Bitmap? {
        val marginX = (rect.width() * marginPercent).toInt()
        val marginY = (rect.height() * marginPercent).toInt()

        val left = max(0, rect.left - marginX)
        val top = max(0, rect.top - marginY)
        val right = min(bitmap.width, rect.right + marginX)
        val bottom = min(bitmap.height, rect.bottom + marginY)

        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return null

        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    fun resize(bitmap: Bitmap, size: Int): Bitmap {
        return Bitmap.createScaledBitmap(bitmap, size, size, true)
    }
}
