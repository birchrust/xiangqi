package dev.xiangqi.assistant.domain.board

import org.junit.Assert.*
import org.junit.Test

class BoardPositionTest {
    private val start = "rnbakabnr" + "........." + ".c.....c." + "p.p.p.p.p" + "........." +
        "........." + "P.P.P.P.P" + ".C.....C." + "........." + "RNBAKABNR"

    @Test fun invertedBoardsHaveTheSameCanonicalPosition() {
        assertEquals(BoardPosition.fromOrientedCells(start, true), BoardPosition.fromOrientedCells(start.reversed(), true))
    }


    @Test fun occlusionsAndMissingKingsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { BoardPosition.fromOrientedCells(start.replaceRange(40, 41, "x"), true) }
        assertThrows(IllegalArgumentException::class.java) { BoardPosition.fromOrientedCells(start.replace('k', '.'), true) }
    }


    @Test fun facingKingsAndWrongTurnDuringCheckAreRejected() {
        val cells = CharArray(90) { '.' }
        cells[4] = 'k'; cells[85] = 'K'
        assertThrows(IllegalArgumentException::class.java) { BoardPosition(String(cells), true).validate() }
        cells[49] = 'P' // Blocks the generals' file.
        cells[13] = 'R' // Red rook checks black; it must be black's turn.
        BoardPosition(String(cells), false).validate()
        assertThrows(IllegalArgumentException::class.java) { BoardPosition(String(cells), true).validate() }
    }


    @Test fun typedMovesPreserveTheOriginalAndSwitchTurns() {
        val before = BoardPosition(start, true)
        val after = before.play(BoardMove(70, 67))
        assertEquals(start, before.cells)
        assertTrue(before.redToMove)
        assertEquals('.', after.cells[70])
        assertEquals('C', after.cells[67])
        assertFalse(after.redToMove)
        assertEquals(after.cells.count { it != '.' }, before.cells.count { it != '.' })
    }

    @Test fun illegalMovesCannotCreateANewPosition() {
        val board = BoardPosition(start, true)
        for (move in listOf(BoardMove(81, 36), BoardMove(0, 9), BoardMove(70, 70))) {
            assertThrows(IllegalArgumentException::class.java) { board.play(move) }
        }
        assertFalse(board.isLegalMove(-1, 0))
        assertFalse(board.isLegalMove(0, 90))
    }

    @Test fun typedMovesRejectSquaresOutsideTheBoard() {
        assertThrows(IllegalArgumentException::class.java) { BoardMove(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { BoardMove(0, 90) }
    }
}
