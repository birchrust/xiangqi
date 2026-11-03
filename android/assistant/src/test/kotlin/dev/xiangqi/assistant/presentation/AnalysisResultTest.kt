package dev.xiangqi.assistant.presentation

import dev.xiangqi.assistant.domain.board.BoardPosition
import dev.xiangqi.assistant.engine.model.EngineAnswer
import dev.xiangqi.assistant.engine.model.EngineCandidate
import dev.xiangqi.assistant.engine.model.UciScore
import org.junit.Assert.*
import org.junit.Test

class AnalysisResultTest {
    private val board = BoardPosition(
        "rnbakabnr" + "........." + ".c.....c." + "p.p.p.p.p" + "........." +
            "........." + "P.P.P.P.P" + ".C.....C." + "........." + "RNBAKABNR", false)

    @Test fun preparesAllCandidatesWithRedScoresAndAlternatingColors() {
        val answer = EngineAnswer("h9g7", listOf(
            EngineCandidate(1, listOf("h9g7", "h2e2"), 8, UciScore("cp", 120)),
            EngineCandidate(2, listOf("b9c7", "b0c2"), 8, UciScore("cp", -40))))
        val result = AnalysisResult(board, answer)
        assertEquals(2, result.candidateCount)
        assertEquals(listOf("马8进7", "炮二平五"), result.variations[0].map { it.text })
        assertEquals(listOf(false, true), result.variations[0].map { it.red })
        assertEquals(listOf("-1.20", "+0.40"), result.scores)
        assertEquals("马2进3", result.variations[1].first().text)
        assertEquals(listOf(MoveHint(7, 24, false), MoveHint(70, 67, true)), result.hints[0])
        assertNotEquals(result.hints[0], result.hints[1])
    }

    @Test fun noLegalMoveStillHasAVisibleResult() {
        val result = AnalysisResult(board, EngineAnswer("(none)", emptyList()))
        assertEquals(0, result.candidateCount)
        assertEquals("无合法招法", result.variations.single().single().text)
        assertEquals(listOf("—"), result.scores)
        assertTrue(result.hints.isEmpty())
    }

    @Test fun invalidVariationIsRejectedBeforePublishing() {
        val answer = EngineAnswer("a0a1", listOf(EngineCandidate(1, listOf("a0a1"), 1, null)))
        assertThrows(IllegalArgumentException::class.java) { AnalysisResult(board, answer) }
    }
}
