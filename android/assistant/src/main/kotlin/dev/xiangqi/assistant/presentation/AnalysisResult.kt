package dev.xiangqi.assistant.presentation

import dev.xiangqi.assistant.domain.board.BoardPosition
import dev.xiangqi.assistant.engine.model.EngineAnswer

/** Prepares validated candidates for UI selection. */
class AnalysisResult(val position: BoardPosition, val answer: EngineAnswer) {
    val candidateCount = answer.candidates.size
    val variations = (0 until maxOf(1, candidateCount)).map { answer.display(position, it) }
    val scores = variations.indices.map { answer.redScore(position, it) }
    val hints = answer.candidates.map { MoveHint.firstForEachSide(position, it.moves) }
}
