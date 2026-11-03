package dev.xiangqi.assistant.engine

import dev.xiangqi.assistant.domain.board.BoardMove
import dev.xiangqi.assistant.domain.board.BoardPosition
import org.junit.Assert.*
import org.junit.Test

class UciNotationTest {
    private val start = "rnbakabnr" + "........." + ".c.....c." + "p.p.p.p.p" + "........." +
        "........." + "P.P.P.P.P" + ".C.....C." + "........." + "RNBAKABNR"

    @Test fun fenIncludesEmptyRanksAndBothTurns() {
        val red = BoardPosition(start, true)
        assertEquals("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1", red.fen())
        assertTrue(red.copy(redToMove = false).fen().endsWith(" b - - 0 1"))
    }

    @Test fun coordinatesAnchorToCanonicalCorners() {
        assertEquals(BoardMove(81, 8), UciNotation.parseMove("a0i9"))
        assertEquals(BoardMove(0, 89), UciNotation.parseMove("a9i0"))
        assertEquals(BoardMove(70, 67), UciNotation.parseMove("h2e2"))
        assertEquals("h9g7", UciNotation.formatMove(BoardMove(7, 24)))
    }

    @Test fun everySquarePairRoundTripsWithoutChangingOrientation() {
        for (from in 0 until BoardPosition.SIZE) {
            for (to in 0 until BoardPosition.SIZE) {
                val move = BoardMove(from, to)
                assertEquals(move, UciNotation.parseMove(UciNotation.formatMove(move)))
            }
        }
    }

    @Test fun malformedAndTerminalTokensAreNotCoordinates() {
        for (value in listOf("", "a0j0", "a0a10", "A0a1", "h2e2 ", "0000", "(none)")) {
            assertFalse(UciNotation.isMove(value))
            assertThrows(IllegalArgumentException::class.java) { UciNotation.parseMove(value) }
        }
    }
}
