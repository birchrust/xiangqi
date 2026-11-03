package dev.xiangqi.assistant.capture

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicBoolean

/** Returns the bitmap after recognition finishes. */
class CapturedFrame internal constructor(val bitmap: Bitmap, private val release: (Bitmap) -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    override fun close() { if (closed.compareAndSet(false, true)) release(bitmap) }
}
