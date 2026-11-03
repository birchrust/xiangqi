package dev.xiangqi.assistant.presentation

import dev.xiangqi.assistant.domain.board.BoardMove
import dev.xiangqi.assistant.domain.board.BoardPosition
import dev.xiangqi.assistant.domain.tracking.Observation
import dev.xiangqi.assistant.domain.tracking.ObservedMove
import org.junit.Assert.*
import org.junit.Test

class ObservationFormattingTest {
    private val start = "rnbakabnr" + "........." + ".c.....c." + "p.p.p.p.p" + "........." +
        "........." + "P.P.P.P.P" + ".C.....C." + "........." + "RNBAKABNR"

    @Test fun formatsStructuredMovesWithoutChangingTheSnapshot() {
        val move = ObservedMove(BoardPosition(start, true), BoardMove(70, 67))
        val observation = Observation(false, move, true)
        assertEquals("红方 炮二平五（h2e2）", observation.lastMoveText)
        assertEquals("观察中 · 黑方走", observation.message)
        assertSame(move, observation.lastMove)
    }

    @Test fun pendingAndInitialBoardsKeepTheirStatus() {
        assertEquals("等待棋盘稳定…", Observation(true, null, false).message)
        val initial = Observation(true, null, true)
        assertEquals("观察中 · 红方走", initial.message)
        assertNull(initial.lastMoveText)
    }
}
