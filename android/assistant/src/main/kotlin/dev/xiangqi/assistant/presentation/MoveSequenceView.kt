package dev.xiangqi.assistant.presentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import kotlin.math.ceil

/** Caches centered, equal-width move columns. */
class MoveSequenceView(context: Context) : View(context) {
    private data class Glyph(val text: String, val x: Float, val baseline: Float, val color: Int)

    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = sp(15f) }
    private val statusPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(13f)
        color = Color.LTGRAY
    }
    private var moves = emptyList<DisplayMove>()
    private var status = ""
    private var glyphs = emptyList<Glyph>()
    private var statusLayout: StaticLayout? = null
    private var layoutDirty = true

    init {
        setPadding(dp(8), dp(4), dp(8), dp(4))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun showMoves(value: List<DisplayMove>) {
        if (moves == value && status.isEmpty()) return
        moves = value
        status = ""
        contentDescription = value.joinToString("，") { it.text }
        layoutDirty = true
        invalidate()
    }

    fun showStatus(value: String) {
        if (moves.isEmpty() && status == value) return
        moves = emptyList()
        status = value
        contentDescription = value
        layoutDirty = true
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(120), widthMeasureSpec), resolveSize(dp(100), heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutDirty = true
    }

    private fun updateLayout() {
        layoutDirty = false
        glyphs = emptyList()
        statusLayout = null
        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom
        if (availableWidth <= 0 || availableHeight <= 0) return
        if (moves.isEmpty()) {
            if (status.isNotEmpty()) statusLayout = StaticLayout.Builder
                .obtain(status, 0, status.length, statusPaint, availableWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
            return
        }
        val metrics = paint.fontMetrics
        val textHeight = ceil(metrics.bottom - metrics.top)
        val lineHeight = textHeight + dp(4)
        val characterWidth = paint.measureText("车")
        val moveWidth = maxOf(4, moves.maxOf { it.text.length }) * characterWidth
        val gap = dp(10)
        val columns = ((availableWidth + gap) / (moveWidth + gap)).toInt().coerceAtMost(moves.size)
        if (columns == 0) return
        val startX = (availableWidth - (columns * moveWidth + (columns - 1) * gap)) / 2
        glyphs = buildList {
            for ((index, move) in moves.withIndex()) {
                val top = (index / columns) * lineHeight
                if (top + textHeight > availableHeight) break
                val x = startX + (index % columns) * (moveWidth + gap)
                val color = if (move.red) Color.rgb(239, 106, 97) else Color.WHITE
                move.text.forEachIndexed { characterIndex, character ->
                    val glyph = character.toString()
                    val glyphX = x + characterIndex * characterWidth + (characterWidth - paint.measureText(glyph)) / 2
                    add(Glyph(glyph, glyphX, top - metrics.top, color))
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (layoutDirty) updateLayout()
        val saved = canvas.save()
        canvas.clipRect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        statusLayout?.draw(canvas)
        for (glyph in glyphs) {
            paint.color = glyph.color
            canvas.drawText(glyph.text, glyph.x, glyph.baseline, paint)
        }
        canvas.restoreToCount(saved)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}
