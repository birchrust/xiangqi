package dev.xiangqi.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.xiangqi.assistant.application.analysis.AnalysisCoordinator
import dev.xiangqi.assistant.application.analysis.AnalysisEngine
import dev.xiangqi.assistant.domain.board.BoardPosition
import dev.xiangqi.assistant.engine.PikafishEngine
import dev.xiangqi.assistant.engine.model.EngineAnswer
import dev.xiangqi.assistant.engine.play
import dev.xiangqi.assistant.presentation.describeVariation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchReuseTest {
    @Test fun realSearchIterationsSurvivePauseDepthChangesAndReturningToAPosition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val board = BoardPosition("rnbakabnr" + "........." + ".c.....c." + "p.p.p.p.p" + "........." +
            "........." + "P.P.P.P.P" + ".C.....C." + "........." + "RNBAKABNR", false)
        val factories = AtomicInteger()
        val searches = AtomicInteger()
        AnalysisCoordinator {
            factories.incrementAndGet()
            val engine = PikafishEngine(context)
            object : AnalysisEngine {
                override fun analyze(position: BoardPosition, maxDepth: Int, onUpdate: (EngineAnswer) -> Unit, cancelled: () -> Boolean) {
                    searches.incrementAndGet()
                    engine.analyze(position, maxDepth, onUpdate, cancelled)
                }
                override fun close() = engine.close()
            }
        }.use { coordinator ->
            fun request(position: BoardPosition, depth: Int) {
                val ready = CountDownLatch(1)
                coordinator.request(position, depth, { _, answer ->
                    answer.candidates.forEach { position.describeVariation(it.moves) }
                    if (answer.candidates.first().depth == depth) ready.countDown()
                }, { _, error -> throw AssertionError(error) })
                assertTrue(ready.await(20, TimeUnit.SECONDS))
            }
            request(board, 6)
            coordinator.cancel(releaseEngine = true)
            request(board, 4)
            request(board, 6)
            assertEquals(1, searches.get())
            assertEquals(1, factories.get())
            request(board.play("h9g7"), 6)
            request(board, 6)
            assertEquals(2, searches.get())
            assertEquals(2, factories.get())
        }
    }
}
