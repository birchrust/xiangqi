package dev.xiangqi.assistant.presentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import dev.xiangqi.assistant.recognition.BoardGeometry
import kotlin.math.hypot

class MoveArrowView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val head = Path()
    private var geometry: BoardGeometry? = null
    private var hints = emptyList<MoveHint>()
    private var displayWidth = 0
    private var displayHeight = 0
    private val location = IntArray(2)
    var captureHidden = false

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun show(board: BoardGeometry?, moves: List<MoveHint>, screenWidth: Int = width, screenHeight: Int = height) {
        if (geometry == board && hints == moves && displayWidth == screenWidth && displayHeight == screenHeight) return
        geometry = board; hints = moves
        displayWidth = screenWidth; displayHeight = screenHeight
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (captureHidden) return
        val board = geometry ?: return
        val density = resources.displayMetrics.density
        if (isAttachedToWindow) getLocationOnScreen(location) else location.fill(0)
        for (hint in hints) {
            val from = board.point(hint.from); val to = board.point(hint.to)
            val x = from.x * displayWidth - location[0]; val y = from.y * displayHeight - location[1]
            val endX = to.x * displayWidth - location[0]; val endY = to.y * displayHeight - location[1]
            val length = hypot(endX - x, endY - y)
            if (length < 1f) continue
            val ux = (endX - x) / length; val uy = (endY - y) / length
            val size = minOf(12 * density, length * 0.35f)
            val baseX = endX - ux * size; val baseY = endY - uy * size
            head.rewind()
            head.moveTo(endX, endY)
            head.lineTo(baseX - uy * size * 0.55f, baseY + ux * size * 0.55f)
            head.lineTo(baseX + uy * size * 0.55f, baseY - ux * size * 0.55f)
            head.close()
            paint.color = Color.rgb(28, 28, 28); paint.style = Paint.Style.STROKE; paint.strokeWidth = 6 * density
            canvas.drawLine(x, y, baseX, baseY, paint)
            canvas.drawCircle(x, y, 4 * density, paint)
            paint.strokeWidth = 2 * density
            canvas.drawPath(head, paint)
            paint.color = if (hint.red) Color.rgb(239, 106, 97) else Color.WHITE
            paint.strokeWidth = 3 * density
            canvas.drawLine(x, y, baseX, baseY, paint)
            canvas.drawCircle(x, y, 4 * density, paint)
            paint.style = Paint.Style.FILL
            canvas.drawPath(head, paint)
        }
    }
}
