package dev.xiangqi.assistant.presentation

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.Gravity
import android.view.WindowManager
import dev.xiangqi.assistant.recognition.BoardGeometry

/** UI-thread overlay; hasHints supports cross-thread reads. */
class MoveArrowOverlay(private val context: Context, private val windows: WindowManager) : AutoCloseable {
    private val view = MoveArrowView(context)
    private var attached = false
    @Volatile var hasHints = false
        private set

    @Suppress("DEPRECATION")
    fun show(geometry: BoardGeometry, hints: List<MoveHint>) {
        if (hints.isEmpty()) { clear(); return }
        if (!attached) {
            val parameters = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                title = "XiangqiMoveArrows"
                // Android 12+ limits opacity for touch-through.
                alpha = if (Build.VERSION.SDK_INT >= 31)
                    minOf(0.5f, context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch) else 0.5f
                if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            }
            windows.addView(view, parameters)
            attached = true
        }
        hasHints = true
        val metrics = DisplayMetrics()
        windows.defaultDisplay.getRealMetrics(metrics)
        view.show(geometry, hints, metrics.widthPixels, metrics.heightPixels)
    }

    fun clear() {
        hasHints = false
        view.show(null, emptyList())
    }

    /** Wait two UI frames before capturing hidden arrows. */
    fun prepareCapture(ready: () -> Unit) {
        view.captureHidden = true
        view.invalidate()
        val choreographer = Choreographer.getInstance()
        choreographer.postFrameCallback {
            choreographer.postFrameCallback {
                ready()
                // Refresh the buffer even for a static board.
                view.invalidate()
            }
        }
    }

    fun finishCapture() { view.captureHidden = false; view.invalidate() }

    override fun close() {
        clear()
        if (attached) windows.removeViewImmediate(view)
        attached = false
    }
}
