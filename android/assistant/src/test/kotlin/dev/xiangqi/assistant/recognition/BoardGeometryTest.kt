package dev.xiangqi.assistant.recognition

import dev.xiangqi.assistant.domain.board.BoardPosition
import dev.xiangqi.assistant.presentation.MoveHint
import org.junit.Assert.*
import org.junit.Test

class BoardGeometryTest {
    private val corners = listOf(BoardPoint(0.1f, 0.2f), BoardPoint(0.9f, 0.2f), BoardPoint(0.1f, 0.8f), BoardPoint(0.9f, 0.8f))
    private val position = BoardPosition(
        "rnbakabnr" + "........." + ".c.....c." + "p.p.p.p.p" + "........." +
            "........." + "P.P.P.P.P" + ".C.....C." + "........." + "RNBAKABNR", false)

    @Test fun mapsCanonicalIntersectionsAndFlippedBoards() {
        val board = BoardGeometry(corners)
        assertEquals(corners[0], board.point(0))
        assertEquals(0.9f, board.point(8).x, 0.0001f)
        assertEquals(0.8f, board.point(89).y, 0.0001f)
        val flipped = BoardGeometry(corners.reversed())
        for (square in 0..89) {
            assertEquals(board.point(89 - square).x, flipped.point(square).x, 0.0001f)
            assertEquals(board.point(89 - square).y, flipped.point(square).y, 0.0001f)
        }
    }

    @Test fun usesPerspectiveRatherThanLinearScreenRows() {
        val board = BoardGeometry(listOf(BoardPoint(0f, 0f), BoardPoint(1f, 0f), BoardPoint(0.25f, 1f), BoardPoint(0.75f, 1f)))
        val center = board.point(3 * 9 + 4)
        assertEquals(0.5f, center.x, 0.0001f)
        assertEquals(0.5f, center.y, 0.0001f)
    }

    @Test fun selectsOnlyTheFirstMoveForEachSideInVariationOrder() {
        val hints = MoveHint.firstForEachSide(position, listOf("h9g7", "h2e2", "a9a8", "b0c2"))
        assertEquals(listOf(MoveHint(7, 24, false), MoveHint(70, 67, true)), hints)
        val redFirst = MoveHint.firstForEachSide(position.copy(redToMove = true), listOf("h0g2"))
        assertEquals(listOf(MoveHint(88, 69, true)), redFirst)
        assertTrue(MoveHint.firstForEachSide(position, emptyList()).isEmpty())
    }

    @Test fun rookAdvanceEndsOneIntersectionAhead() {
        val hints = MoveHint.firstForEachSide(position, listOf("a9a8", "a0a1"))
        val board = BoardGeometry(corners)
        assertEquals(0.6f / 9, board.point(hints[0].to).y - board.point(hints[0].from).y, 0.0001f)
        assertEquals(-0.6f / 9, board.point(hints[1].to).y - board.point(hints[1].from).y, 0.0001f)
    }
}
