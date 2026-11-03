package dev.xiangqi.assistant.engine

import org.junit.Assert.*
import org.junit.Test

class UciAnalysisTest {
    @Test fun fourCandidatesHaveDifferentFirstMoves() {
        val parser = UciAnalysis()
        val firsts = listOf("h9g7", "b9c7", "h7e7", "b7e7")
        for ((index, move) in firsts.withIndex()) {
            parser.accept("info depth 8 multipv ${index + 1} score cp ${100 - index * 10} pv $move h2e2 a9a8 b0c2")
        }
        val result = parser.finish("h9g7")
        assertEquals(firsts, result.map { it.moves.first() })
        assertEquals(4, result.map { it.moves.first() }.distinct().size)
        assertTrue(result.all { it.moves.size == 4 })
    }

    @Test fun incompleteIterationsCannotDuplicateFirstMoves() {
        val parser = UciAnalysis()
        parser.accept("info depth 6 multipv 1 score cp 80 pv h9g7 h2e2")
        parser.accept("info depth 6 multipv 2 score cp 70 pv b9c7 h2e2")
        parser.accept("info depth 7 multipv 1 score cp 90 pv b9c7 h2e2")
        val result = parser.finish("b9c7")
        assertEquals("b9c7", result.first().moves.first())
        assertEquals(result.size, result.map { it.moves.first() }.distinct().size)
        assertTrue(parser.finish("(none)").isEmpty())
    }

    @Test fun retainsTheFullVariationForAdaptiveDisplay() {
        val parser = UciAnalysis()
        val moves = "h9g7 h2e2 b9c7 b0c2 a9a8 h0g2"
        parser.accept("info depth 12 multipv 1 score cp 80 pv $moves")
        assertEquals(moves.split(' '), parser.finish("h9g7").single().moves)
    }

    @Test fun streamingPublishesOnlyCompleteMultiPvDepths() {
        val parser = UciAnalysis()
        val moves = listOf("h9g7", "b9c7", "h7e7", "b7e7")
        for (index in 0..2) parser.accept("info depth 5 multipv ${index + 1} score cp 20 pv ${moves[index]}")
        assertTrue(parser.current().isEmpty())
        parser.accept("info depth 5 multipv 4 score cp 10 pv ${moves[3]}")
        assertEquals(4, parser.current().size)
        parser.accept("info depth 6 multipv 1 score cp 30 pv ${moves[0]}")
        assertEquals(5, parser.current().first().depth)
        for (index in 1..3) parser.accept("info depth 6 multipv ${index + 1} score cp 20 pv ${moves[index]}")
        assertEquals(6, parser.current().first().depth)
        assertEquals(4, parser.current().map { it.moves.first() }.distinct().size)
        val snapshot = parser.current()
        repeat(20) {
            assertFalse(parser.accept("info nodes 10000 nps 30000 currmove h9g7"))
            assertSame(snapshot, parser.current())
        }
        assertFalse(parser.accept("info depth 6 multipv 4 score cp 20 pv ${moves[3]}"))
        assertSame(snapshot, parser.current())
    }
}
