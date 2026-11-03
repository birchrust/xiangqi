package dev.xiangqi.assistant

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.xiangqi.assistant.domain.board.BoardPosition
import dev.xiangqi.assistant.engine.PikafishEngine
import dev.xiangqi.assistant.engine.model.EngineAnswer
import dev.xiangqi.assistant.engine.play
import dev.xiangqi.assistant.presentation.DisplayMove
import dev.xiangqi.assistant.presentation.MoveSequenceView
import dev.xiangqi.assistant.presentation.describeVariation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeOptimizationTest {
    private val board = BoardPosition(
        "rnbakabnr" + "........." + ".c.....c." + "p.p.p.p.p" + "........." +
            "........." + "P.P.P.P.P" + ".C.....C." + "........." + "RNBAKABNR", false)

    @Test fun continuousSearchStreamsWholeIterationsAndStopsAtDepth() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PikafishEngine(context).use { engine ->
            val answers = mutableListOf<EngineAnswer>()
            engine.analyze(board, 6, { answers.add(it) }, { false })
            assertTrue(answers.size >= 2)
            val depths = answers.map { it.candidates.first().depth }
            assertEquals(6, depths.last())
            assertTrue(depths.zipWithNext().all { (before, after) -> after > before })
            for (answer in answers) {
                assertEquals(4, answer.candidates.size)
                assertEquals(4, answer.candidates.map { it.moves.first() }.distinct().size)
                assertTrue(answer.candidates.all { it.depth <= 6 })
                answer.candidates.forEach { board.describeVariation(it.moves) }
            }
        }
    }

    @Test fun aMoveCancelsTheOldSearchAndReusesTheProcessForTheNextPosition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PikafishEngine(context).use { engine ->
            val cancelled = AtomicBoolean(false)
            var updates = 0
            engine.analyze(board, 128, { updates++; cancelled.set(true) }, { cancelled.get() })
            assertEquals(1, updates)
            // The old bestmove must be consumed before issuing the next go.
            val next = board.play("h9g7")
            val answer = engine.bestMove(next, maxDepth = 6)
            assertEquals(6, answer.candidates.first().depth)
            assertEquals(4, answer.candidates.size)
            answer.candidates.forEach { next.describeVariation(it.moves) }
        }
    }

    @Test fun closingEngineInterruptsSearchAndPreventsRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cells = "rnbakabnr" + "........." + ".c.....c." + "p.p.p.p.p" + "........." +
            "........." + "P.P.P.P.P" + ".C.....C." + "........." + "RNBAKABNR"
        val board = BoardPosition(cells, false)
        val executor = Executors.newSingleThreadExecutor()
        PikafishEngine(context).use { engine ->
            engine.bestMove(board, maxDepth = 1)
            val started = CountDownLatch(1)
            val searching = executor.submit<EngineAnswer> { started.countDown(); engine.bestMove(board, maxDepth = 128) }
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS))
                engine.close()
                val error = assertThrows(ExecutionException::class.java) { searching.get(3, TimeUnit.SECONDS) }
                assertTrue(error.cause is Exception)
                assertThrows(IllegalStateException::class.java) { engine.bestMove(board) }
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun cachedMovesRemainCenteredAndReflowOnResize() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = MoveSequenceView(instrumentation.targetContext)
            val density = view.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            fun draw(width: Int): Bitmap {
                view.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(120), View.MeasureSpec.EXACTLY))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            }
            fun pixels(image: Bitmap) = IntArray(image.width * image.height).also {
                image.getPixels(it, 0, image.width, 0, 0, image.width, image.height)
            }
            val moves = List(8) { DisplayMove("车九进一", it % 2 == 0) }
            view.showMoves(moves)
            val wide = draw(300)
            val repeated = draw(300)
            val narrow = draw(180)
            try {
                assertArrayEquals(pixels(wide), pixels(repeated))
                fun bounds(image: Bitmap): Pair<Int, Int> {
                    val data = pixels(image)
                    val ink = data.indices.filter { Color.alpha(data[it]) > 0 }
                    return (ink.maxOf { it % image.width } + ink.minOf { it % image.width }) / 2 to
                        (ink.maxOf { it / image.width } - ink.minOf { it / image.width })
                }
                val wideBounds = bounds(wide)
                val narrowBounds = bounds(narrow)
                assertTrue(abs(wideBounds.first - wide.width / 2) < dp(3))
                assertTrue(abs(narrowBounds.first - narrow.width / 2) < dp(3))
                assertTrue(narrowBounds.second > wideBounds.second)
                view.showStatus("等待棋盘")
                val status = draw(180)
                try { assertFalse(pixels(status).any { it == Color.rgb(239, 106, 97) || it == Color.WHITE }) }
                finally { status.recycle() }
            } finally { wide.recycle(); repeated.recycle(); narrow.recycle() }
        }
    }
}
