package dev.xiangqi.assistant.presentation

import dev.xiangqi.assistant.domain.board.BoardPosition
import dev.xiangqi.assistant.engine.UciNotation

data class MoveHint(val from: Int, val to: Int, val red: Boolean) {
    companion object {
        fun firstForEachSide(position: BoardPosition, moves: List<String>): List<MoveHint> =
            moves.take(2).mapIndexed { index, value ->
                val move = UciNotation.parseMove(value)
                MoveHint(move.from, move.to, if (index == 0) position.redToMove else !position.redToMove)
            }
    }
}
