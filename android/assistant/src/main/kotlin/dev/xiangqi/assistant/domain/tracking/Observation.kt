package dev.xiangqi.assistant.domain.tracking

import dev.xiangqi.assistant.domain.board.BoardMove
import dev.xiangqi.assistant.domain.board.BoardPosition

data class ObservedMove(val position: BoardPosition, val move: BoardMove) {
    val red: Boolean get() = position.redToMove
}

data class Observation(val redToMove: Boolean, val lastMove: ObservedMove?, val ready: Boolean)
