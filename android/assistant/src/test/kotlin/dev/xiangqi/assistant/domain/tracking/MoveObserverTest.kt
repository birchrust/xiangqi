package dev.xiangqi.assistant.domain.tracking

import dev.xiangqi.assistant.domain.board.BoardMove
import org.junit.Assert.*
import org.junit.Test

class MoveObserverTest {
    private val start = "rnbakabnr" + "........." + ".c.....c." + "p.p.p.p.p" + "........." +
        "........." + "P.P.P.P.P" + ".C.....C." + "........." + "RNBAKABNR"

    private fun move(cells: String, uci: String): String {
        val from = (9 - (uci[1] - '0')) * 9 + (uci[0] - 'a')
        val to = (9 - (uci[3] - '0')) * 9 + (uci[2] - 'a')
        return String(cells.toCharArray().apply { this[to] = this[from]; this[from] = '.' })
    }

    @Test fun staticBoardKeepsSeedAndNeverInventsMoves() {
        val observer = MoveObserver(false)
        assertFalse(observer.accept(start).redToMove)
        val unchanged = observer.accept(start)
        assertFalse(unchanged.redToMove)
        assertNull(unchanged.lastMove)
        assertTrue(unchanged.ready)
    }

    @Test fun tracksBlackThenRedMoves() {
        val observer = MoveObserver(false)
        observer.accept(start)
        val blackMove = move(start, "h9g7")
        val afterBlack = observer.accept(blackMove)
        assertTrue(afterBlack.redToMove)
        assertFalse(afterBlack.lastMove!!.red)
        val afterRed = observer.accept(move(blackMove, "h2e2"))
        assertFalse(afterRed.redToMove)
        assertEquals(BoardMove(70, 67), afterRed.lastMove!!.move)
        assertTrue(afterRed.lastMove!!.red)
    }

    @Test fun aRealMoveCorrectsAnInitiallyWrongSeed() {
        val observer = MoveObserver(false)
        observer.accept(start)
        assertFalse(observer.accept(move(start, "h2e2")).redToMove)
    }

    @Test fun recognizesCaptureAndSwitchesToTheOpponent() {
        val board = move(start, "h2e2")
        val observer = MoveObserver(true)
        observer.accept(board)
        val captured = observer.accept(move(board, "e2e6"))
        assertTrue(captured.ready)
        assertFalse(captured.redToMove)
        assertEquals(BoardMove(67, 31), captured.lastMove!!.move)
    }

    @Test fun rejectsTeleportWithoutReplacingTheBaseline() {
        val observer = MoveObserver(true)
        observer.accept(start)
        assertFalse(observer.accept(move(start, "a0a5")).ready) // Rook cannot pass its pawn.
        assertTrue(observer.accept(start).ready)
        assertFalse(observer.accept(move(start, "h2e2")).redToMove)
    }

    @Test fun recoversTwoLegalMovesBetweenSampledFrames() {
        val fresh = MoveObserver(false)
        fresh.accept(start)
        val blackMove = move(start, "h9g7")
        val twoMoves = move(blackMove, "h2e2")
        assertTrue(fresh.accept(twoMoves).ready)
        assertFalse(fresh.accept(twoMoves).redToMove)
        val recovered = fresh.accept(move(twoMoves, "b9c7"))
        assertTrue(recovered.ready)
        assertTrue(recovered.redToMove)
    }

    @Test fun partialOcclusionDoesNotPoisonTheNextRealMove() {
        val observer = MoveObserver(false)
        observer.accept(start)
        val occluded = start.replaceRange(64, 65, ".")
        repeat(3) { assertFalse(observer.accept(occluded).ready) }
        val actual = observer.accept(move(start, "h9g7"))
        assertTrue(actual.ready)
        assertTrue(actual.redToMove)
        assertEquals(BoardMove(7, 24), actual.lastMove!!.move)
    }

    @Test fun synchronizedTurnsRejectASecondMoveByTheSameSide() {
        val observer = MoveObserver(true)
        observer.accept(start)
        val afterRed = move(start, "h2e2")
        assertFalse(observer.accept(afterRed).redToMove)
        assertFalse(observer.accept(start).ready)
        assertFalse(observer.accept(move(afterRed, "b2c2")).ready)
        val afterBlack = observer.accept(move(afterRed, "h9g7"))
        assertTrue(afterBlack.ready)
        assertTrue(afterBlack.redToMove)
    }
}
