package dev.xiangqi.assistant

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.xiangqi.assistant.capture.ScreenFrameBuffer
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenFrameBufferTest {
    private fun pixels(width: Int, height: Int, rowStride: Int, color: Int, offset: Int = 0): ByteBuffer {
        val source = ByteBuffer.allocate(offset + rowStride * (height - 1) + width * 4)
        for (row in 0 until height) for (col in 0 until width) {
            val i = offset + row * rowStride + col * 4
            source.put(i, Color.red(color).toByte())
            source.put(i + 1, Color.green(color).toByte())
            source.put(i + 2, Color.blue(color).toByte())
            source.put(i + 3, Color.alpha(color).toByte())
        }
        source.position(offset)
        return source
    }

    @Test fun stripsRowPaddingAndHandlesMissingFinalPaddingWithoutChangingSource() {
        ScreenFrameBuffer().use { buffer ->
            val source = pixels(2, 2, 12, Color.RED, offset = 3)
            val limit = source.limit()
            buffer.convert(2, 2, 12, 4, source).use { frame ->
                assertEquals(2, frame.bitmap.width)
                assertEquals(2, frame.bitmap.height)
                for (row in 0..1) for (col in 0..1) assertEquals(Color.RED, frame.bitmap.getPixel(col, row))
            }
            assertEquals(3, source.position())
            assertEquals(limit, source.limit())
            assertThrows(IllegalArgumentException::class.java) { buffer.convert(2, 2, 12, 4, ByteBuffer.allocate(8)) }
            buffer.convert(2, 2, 8, 4, pixels(2, 2, 8, Color.BLUE)).use {
                assertEquals(Color.BLUE, it.bitmap.getPixel(1, 1))
            }
        }
    }

    @Test fun workerReturnsTheBitmapForReuseWithoutRecyclingAnOutstandingFrame() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            ScreenFrameBuffer().use { buffer ->
                val first = buffer.convert(2, 2, 8, 4, pixels(2, 2, 8, Color.RED))
                val bitmap = first.bitmap
                worker.submit { first.close(); first.close() }.get(2, TimeUnit.SECONDS)
                val next = buffer.convert(2, 2, 8, 4, pixels(2, 2, 8, Color.GREEN))
                assertSame(bitmap, next.bitmap)
                assertEquals(Color.GREEN, bitmap.getPixel(1, 1))
                buffer.close()
                assertFalse(bitmap.isRecycled)
                assertEquals(Color.GREEN, bitmap.getPixel(0, 0))
                next.close()
                assertTrue(bitmap.isRecycled)
                assertThrows(IllegalStateException::class.java) { buffer.convert(2, 2, 8, 4, pixels(2, 2, 8, Color.RED)) }
            }
        } finally { worker.shutdownNow() }
    }

    @Test fun resizingDiscardsOnlyOldSizedBuffersWhenTheyAreReturned() {
        ScreenFrameBuffer().use { buffer ->
            val old = buffer.convert(2, 2, 8, 4, pixels(2, 2, 8, Color.RED))
            val resized = buffer.convert(3, 2, 12, 4, pixels(3, 2, 12, Color.BLUE))
            assertFalse(old.bitmap.isRecycled)
            old.close()
            assertTrue(old.bitmap.isRecycled)
            resized.close()
            buffer.convert(3, 2, 12, 4, pixels(3, 2, 12, Color.GREEN)).use { reused ->
                assertSame(resized.bitmap, reused.bitmap)
                assertEquals(Color.GREEN, reused.bitmap.getPixel(2, 1))
            }
        }
    }
}
