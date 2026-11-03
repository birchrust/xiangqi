package dev.xiangqi.assistant

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.xiangqi.assistant.presentation.MoveArrowView
import dev.xiangqi.assistant.presentation.MoveHint
import dev.xiangqi.assistant.recognition.BoardGeometry
import dev.xiangqi.assistant.recognition.BoardPoint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoveArrowViewTest {
    @Test fun arrowsFollowGridCoordinatesAndLeaveCleanFramesForRecognition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = MoveArrowView(instrumentation.targetContext)
            view.layout(0, 0, 900, 1000)
            val board = BoardGeometry(listOf(BoardPoint(0.1f, 0.1f), BoardPoint(0.9f, 0.1f), BoardPoint(0.1f, 0.9f), BoardPoint(0.9f, 0.9f)))
            view.show(board, listOf(MoveHint(0, 9, false), MoveHint(88, 69, true)))
            fun pixels(): IntArray {
                val bitmap = Bitmap.createBitmap(900, 1000, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(bitmap))
                    return IntArray(900 * 1000).also { bitmap.getPixels(it, 0, 900, 0, 0, 900, 1000) }
                } finally { bitmap.recycle() }
            }
            val visible = pixels()
            assertEquals(Color.WHITE, visible[120 * 900 + 90])
            assertTrue(visible.any { it == Color.rgb(239, 106, 97) })
            assertEquals(Color.TRANSPARENT, visible[500 * 900 + 450])
            view.captureHidden = true
            assertTrue(pixels().all { Color.alpha(it) == 0 })
            view.captureHidden = false
            assertArrayEquals(visible, pixels())
            // Use captured display coordinates despite overlay insets.
            view.show(board, listOf(MoveHint(88, 69, true)), 900, 1100)
            assertEquals(Color.rgb(239, 106, 97), pixels()[990 * 900 + 720])
            view.show(null, emptyList())
            assertTrue(pixels().all { Color.alpha(it) == 0 })
        }
    }
}
