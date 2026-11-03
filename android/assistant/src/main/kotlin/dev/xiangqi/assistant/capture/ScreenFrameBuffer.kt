package dev.xiangqi.assistant.capture

import android.graphics.Bitmap
import java.nio.ByteBuffer

/** Capture-thread conversion with reusable buffers. */
class ScreenFrameBuffer : AutoCloseable {
    private val lock = Any()
    private var cached: Bitmap? = null
    private var width = 0
    private var height = 0
    private var closed = false
    private var rows: ByteBuffer? = null

    fun convert(width: Int, height: Int, rowStride: Int, pixelStride: Int, source: ByteBuffer): CapturedFrame {
        require(width > 0 && height > 0 && pixelStride == 4 && rowStride >= width * 4) { "屏幕像素格式异常" }
        val rowBytes = width * 4
        require(source.remaining().toLong() >= rowStride.toLong() * (height - 1) + rowBytes) { "屏幕像素数据不完整" }
        val bitmap = synchronized(lock) {
            check(!closed) { "屏幕捕获已停止" }
            this.width = width; this.height = height
            val previous = cached
            cached = null
            if (previous != null && previous.width == width && previous.height == height) previous else {
                previous?.recycle()
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
        }
        val frame = CapturedFrame(bitmap, ::recycle)
        try {
            val input = source.duplicate()
            val pixels = if (rowStride == rowBytes) input else {
                val size = rowBytes * height
                val packed = rows?.takeIf { it.capacity() >= size }
                    ?: ByteBuffer.allocateDirect(size).also { rows = it }
                packed.clear()
                packed.limit(size)
                val offset = input.position()
                for (row in 0 until height) {
                    input.limit(offset + row * rowStride + rowBytes)
                    input.position(offset + row * rowStride)
                    packed.put(input)
                }
                packed.rewind()
                packed
            }
            bitmap.copyPixelsFromBuffer(pixels)
            return frame
        } catch (error: Throwable) { frame.close(); throw error }
    }

    private fun recycle(bitmap: Bitmap) = synchronized(lock) {
        if (closed || bitmap.width != width || bitmap.height != height || cached != null) bitmap.recycle()
        else cached = bitmap
    }

    override fun close() = synchronized(lock) {
        closed = true
        cached?.recycle()
        cached = null
        rows = null
        // Leased frames remain valid until returned.
    }
}
