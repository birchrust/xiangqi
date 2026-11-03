package dev.xiangqi.assistant.application.analysis

import dev.xiangqi.assistant.domain.board.BoardPosition
import dev.xiangqi.assistant.engine.model.EngineAnswer
import dev.xiangqi.assistant.engine.model.EngineCandidate
import dev.xiangqi.assistant.engine.model.UciScore
import dev.xiangqi.assistant.engine.play
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class AnalysisCoordinatorTest {
    private val board = BoardPosition(
        "rnbakabnr" + "........." + ".c.....c." + "p.p.p.p.p" + "........." +
            "........." + "P.P.P.P.P" + ".C.....C." + "........." + "RNBAKABNR", false)
    private val answer = EngineAnswer("h9g7", listOf(EngineCandidate(1, listOf("h9g7"), 20, UciScore("cp", 0))))
    private class FakeEngine(val search: (BoardPosition, (EngineAnswer) -> Unit, () -> Boolean) -> Unit) : AnalysisEngine {
        val closed = CountDownLatch(1)
        override fun analyze(position: BoardPosition, maxDepth: Int, onUpdate: (EngineAnswer) -> Unit, cancelled: () -> Boolean) =
            search(position, onUpdate, cancelled)
        override fun close() { closed.countDown() }
    }

    @Test fun samePositionDoesNotRestartAfterReachingDepth() {
        val calls = AtomicInteger()
        val done = CountDownLatch(1)
        val engine = FakeEngine { _, update, _ -> calls.incrementAndGet(); update(answer); done.countDown() }
        AnalysisCoordinator { engine }.use { coordinator ->
            assertTrue(coordinator.request(board, 20, { _, _ -> }, { _, e -> throw AssertionError(e) }))
            assertTrue(done.await(2, TimeUnit.SECONDS))
            repeat(20) { assertFalse(coordinator.request(board, 20, { _, _ -> fail("restarted") }, { _, _ -> })) }
            assertEquals(1, calls.get())
            assertTrue(coordinator.request(board, 21, { _, _ -> }, { _, _ -> }))
        }
    }

    @Test fun newerPositionsCancelOldUpdatesAndReuseTheEngine() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val factories = AtomicInteger()
        val positions = CopyOnWriteArrayList<BoardPosition>()
        val delivered = CopyOnWriteArrayList<BoardPosition>()
        val next = board.play("h9g7")
        val latest = next.play("h2e2")
        val engine = FakeEngine { position, update, cancelled ->
            positions.add(position)
            if (position == board) {
                started.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
                assertTrue(cancelled())
                update(answer) // Must be filtered even if the old search emits late.
            } else { update(answer); done.countDown() }
        }
        AnalysisCoordinator { factories.incrementAndGet(); engine }.use { coordinator ->
            try {
                coordinator.request(board, 20, { _, _ -> delivered.add(board) }, { _, _ -> })
                assertTrue(started.await(2, TimeUnit.SECONDS))
                coordinator.request(next, 20, { _, _ -> delivered.add(next) }, { _, _ -> })
                coordinator.request(latest, 20, { _, _ -> delivered.add(latest) }, { _, _ -> })
            } finally { release.countDown() }
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(board, latest), positions.toList())
            assertEquals(listOf(latest), delivered.toList())
            assertEquals(1, factories.get())
        }
    }

    @Test fun stoppingReleasesTheEngineAndAllowsAnExplicitRestart() {
        val started = CountDownLatch(1)
        val restarted = CountDownLatch(1)
        val factories = AtomicInteger()
        lateinit var first: FakeEngine
        first = FakeEngine { _, update, _ ->
            started.countDown()
            assertTrue(first.closed.await(2, TimeUnit.SECONDS))
            update(answer)
        }
        AnalysisCoordinator {
            if (factories.incrementAndGet() == 1) first else FakeEngine { _, update, _ -> update(answer); restarted.countDown() }
        }.use { coordinator ->
            coordinator.request(board, 20, { _, _ -> fail("stale result") }, { _, _ -> })
            assertTrue(started.await(2, TimeUnit.SECONDS))
            coordinator.cancel(releaseEngine = true)
            assertEquals(0L, first.closed.count)
            coordinator.request(board, 20, { _, _ -> }, { _, _ -> })
            assertTrue(restarted.await(2, TimeUnit.SECONDS))
            assertEquals(2, factories.get())
        }
    }

    @Test fun aCompletedPositionSurvivesPauseWithoutCreatingAnotherEngine() {
        val factories = AtomicInteger()
        val calls = AtomicInteger()
        val first = CountDownLatch(1)
        val restored = CountDownLatch(1)
        AnalysisCoordinator {
            factories.incrementAndGet()
            FakeEngine { _, update, _ -> calls.incrementAndGet(); update(answer) }
        }.use { coordinator ->
            coordinator.request(board, 20, { _, _ -> first.countDown() }, { _, _ -> })
            assertTrue(first.await(2, TimeUnit.SECONDS))
            coordinator.cancel(releaseEngine = true)
            coordinator.request(board, 20, { _, value -> assertEquals(answer, value); restored.countDown() }, { _, _ -> })
            assertTrue(restored.await(2, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
            assertEquals(1, factories.get())
        }
    }

    @Test fun aPreviousPositionIsCachedButTheOtherSideToMoveIsADifferentKey() {
        val calls = AtomicInteger()
        AnalysisCoordinator { FakeEngine { _, update, _ -> calls.incrementAndGet(); update(answer) } }.use { coordinator ->
            for (position in listOf(board, board.copy(redToMove = true), board)) {
                val ready = CountDownLatch(1)
                coordinator.request(position, 20, { _, _ -> ready.countDown() }, { _, _ -> })
                assertTrue(ready.await(2, TimeUnit.SECONDS))
            }
            assertEquals(2, calls.get())
        }
    }

    @Test fun loweringDepthUsesAnExistingIterationAndRaisingItBackUsesTheFinalResult() {
        val calls = AtomicInteger()
        val shallow = answer.copy(candidates = answer.candidates.map { it.copy(depth = 6) })
        AnalysisCoordinator { FakeEngine { _, update, _ -> calls.incrementAndGet(); update(shallow); update(answer) } }.use { coordinator ->
            for (depth in listOf(20, 6, 20)) {
                val ready = CountDownLatch(1)
                coordinator.request(board, depth, { _, value ->
                    if (value.candidates.first().depth == depth) ready.countDown()
                }, { _, _ -> })
                assertTrue(ready.await(2, TimeUnit.SECONDS))
            }
            assertEquals(1, calls.get())
        }
    }

    @Test fun increasingDepthLetsTheCurrentSearchFinishInsteadOfCancellingIt() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val depths = CopyOnWriteArrayList<Int>()
        val engine = object : AnalysisEngine {
            override fun analyze(position: BoardPosition, maxDepth: Int, onUpdate: (EngineAnswer) -> Unit, cancelled: () -> Boolean) {
                depths.add(maxDepth)
                if (maxDepth == 6) {
                    started.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                    assertFalse(cancelled())
                }
                onUpdate(answer.copy(candidates = answer.candidates.map { it.copy(depth = maxDepth) }))
            }
            override fun close() { release.countDown() }
        }
        AnalysisCoordinator { engine }.use { coordinator ->
            try {
                coordinator.request(board, 6, { _, _ -> }, { _, _ -> })
                assertTrue(started.await(2, TimeUnit.SECONDS))
                coordinator.request(board, 8, { _, value -> if (value.candidates.first().depth == 8) finished.countDown() }, { _, _ -> })
            } finally { release.countDown() }
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(6, 8), depths.toList())
        }
    }

    @Test fun loweringDepthDuringSearchStopsWhenTheRequestedIterationIsAvailable() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val calls = AtomicInteger()
        val engine = FakeEngine { _, update, cancelled ->
            calls.incrementAndGet(); started.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
            assertFalse(cancelled())
            update(answer.copy(candidates = answer.candidates.map { it.copy(depth = 6) }))
            assertTrue(cancelled())
            stopped.countDown()
        }
        AnalysisCoordinator { engine }.use { coordinator ->
            try {
                coordinator.request(board, 20, { _, _ -> }, { _, _ -> })
                assertTrue(started.await(2, TimeUnit.SECONDS))
                coordinator.request(board, 6, { _, value -> assertEquals(6, value.candidates.first().depth); ready.countDown() }, { _, _ -> })
            } finally { release.countDown() }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            assertTrue(stopped.await(2, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        }
    }

    @Test fun aFailedSearchDoesNotLoopOnEveryFrame() {
        val calls = AtomicInteger()
        val failed = CountDownLatch(1)
        AnalysisCoordinator { FakeEngine { _, _, _ -> calls.incrementAndGet(); error("failed") } }.use { coordinator ->
            coordinator.request(board, 20, { _, _ -> fail() }, { _, _ -> failed.countDown() })
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            repeat(20) { assertFalse(coordinator.request(board, 20, { _, _ -> }, { _, _ -> })) }
            assertEquals(1, calls.get())
        }
    }
}
