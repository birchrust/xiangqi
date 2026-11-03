package dev.xiangqi.assistant.capture

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import dev.xiangqi.assistant.presentation.display
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class ScreenCapture(
    private val context: Context,
    private val onFrame: (CapturedFrame) -> Unit,
    private val onStopped: () -> Unit,
    private val shouldProcess: () -> Boolean = { true },
    private val prepareFrame: ((() -> Unit) -> Boolean) = { false },
    private val frameCaptured: () -> Unit = {}
) : AutoCloseable {
    private val thread = HandlerThread("xiangqi-screen-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var lastFrameAt = 0L
    private val frameBuffer = ScreenFrameBuffer()
    private var preparingFrame = false
    private var frameReady = false
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { if (!closed.get()) onStopped() }
        override fun onCapturedContentResize(width: Int, height: Int) {
            handler.post { if (!closed.get()) resize(width, height) }
        }
    }

    @Suppress("DEPRECATION")
    fun start(resultCode: Int, data: Intent) {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val projection = manager.getMediaProjection(resultCode, data)
        this.projection = projection
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        val metrics = DisplayMetrics()
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        createReader(metrics.widthPixels, metrics.heightPixels)
        // Android 14+ allows one display per consent token.
        display = projection.createVirtualDisplay("XiangqiScreenRecognition", frameWidth, frameHeight,
            metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
    }

    private fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val size = dimensions(width, height)
        if (size.first == frameWidth && size.second == frameHeight) return
        display?.surface = null
        reader?.close()
        createReader(width, height)
        display?.resize(frameWidth, frameHeight, context.resources.displayMetrics.densityDpi)
        display?.surface = reader!!.surface
    }

    private fun dimensions(width: Int, height: Int): Pair<Int, Int> {
        val ratio = minOf(1f, 1280f / maxOf(width, height))
        return (width * ratio).roundToInt().coerceAtLeast(1) to (height * ratio).roundToInt().coerceAtLeast(1)
    }

    private fun createReader(width: Int, height: Int) {
        val size = dimensions(width, height)
        frameWidth = size.first; frameHeight = size.second
        reader = ImageReader.newInstance(frameWidth, frameHeight, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ source ->
                if (closed.get()) return@setOnImageAvailableListener
                val image = try { source.acquireLatestImage() } catch (_: IllegalStateException) { null }
                    ?: return@setOnImageAvailableListener
                try {
                    val now = SystemClock.elapsedRealtime()
                    var deliver = false
                    if (preparingFrame && frameReady) {
                        preparingFrame = false
                        deliver = shouldProcess()
                        if (!deliver) frameCaptured()
                    } else if (!preparingFrame && now - lastFrameAt >= FRAME_INTERVAL_MS && shouldProcess()) {
                        lastFrameAt = now
                        preparingFrame = true
                        frameReady = false
                        if (!prepareFrame { handler.post { if (!closed.get()) frameReady = true } }) {
                            preparingFrame = false
                            deliver = true
                        }
                    }
                    if (deliver) try {
                        val plane = image.planes[0]
                        val frame = frameBuffer.convert(image.width, image.height, plane.rowStride, plane.pixelStride, plane.buffer)
                        try { onFrame(frame) }
                        catch (error: RuntimeException) {
                            frame.close()
                            throw error
                        }
                    } finally { frameCaptured() }
                } catch (error: RuntimeException) {
                    preparingFrame = false
                    frameCaptured()
                    // Bad frames retain capture consent.
                    Log.w("XiangqiAssistant", "Skipping unreadable screen frame", error)
                } finally { image.close() }
            }, handler)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val old = projection
        projection = null
        release("projection callback") { old?.unregisterCallback(callback) }
        release("projection") { old?.stop() }
        handler.post {
            release("virtual display") { display?.release() }
            display = null
            release("image reader") { reader?.close() }
            reader = null
            frameBuffer.close()
            thread.quitSafely()
        }
    }

    private inline fun release(name: String, action: () -> Unit) {
        try { action() }
        catch (error: RuntimeException) { Log.w("XiangqiAssistant", "Unable to release $name", error) }
    }

    companion object {
        // Two matching samples confirm a move.
        private const val FRAME_INTERVAL_MS = 300L
    }
}
