package dev.xiangqi.assistant.presentation

import dev.xiangqi.assistant.domain.board.BoardPosition
import org.junit.Assert.*
import org.junit.Test

class ChineseNotationTest {
    private val start = "rnbakabnr" + "........." + ".c.....c." + "p.p.p.p.p" + "........." +
        "........." + "P.P.P.P.P" + ".C.....C." + "........." + "RNBAKABNR"

    @Test fun chineseMovesUseTheMovingPlayersPerspective() {
        assertEquals("炮二平五（h2e2）", BoardPosition(start, true).describeMove("h2e2"))
        assertEquals("马八进七（b0c2）", BoardPosition(start, true).describeMove("b0c2"))
        assertEquals("炮2平5（b7e7）", BoardPosition(start, false).describeMove("b7e7"))
    }


    @Test fun malformedAndWrongColorEngineMovesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { BoardPosition(start, true).describeMove("i9j9") }
        assertThrows(IllegalArgumentException::class.java) { BoardPosition(start, true).describeMove("a9a8") }
        assertEquals("无合法招法（将死或困毙）", BoardPosition(start, true).describeMove("(none)"))
    }


    @Test fun fullVariationKeepsSequentialAlternatingMoves() {
        val lines = BoardPosition(start, false).describeVariation(listOf("h9g7", "h2e2", "b9c7", "b0c2", "a9a8", "h0g2"))
        assertEquals(listOf(false, true, false, true, false, true), lines.map { it.red })
        assertEquals(listOf("马8进7", "炮二平五", "马2进3", "马八进七"), lines.take(4).map { it.text })
        assertEquals(6, lines.size)
        assertEquals(2, BoardPosition(start, false).describeVariation(listOf("h9g7", "h2e2")).size)
    }
}
