package dev.xiangqi.assistant.domain.tracking

import dev.xiangqi.assistant.domain.board.BoardPosition

/** Advances the baseline only after a legal transition. */
class MoveObserver(initialRedToMove: Boolean) {
    private var previous: String? = null
    private var redToMove = initialRedToMove
    private var lastMove: ObservedMove? = null
    private var turnKnown = false

    fun accept(cells: String): Observation {
        BoardPosition(cells, redToMove).validate(checkTurn = false)
        val before = previous
        if (before == null) {
            try {
                BoardPosition(cells, redToMove).validate()
            } catch (_: IllegalArgumentException) {
                BoardPosition(cells, !redToMove).validate()
                redToMove = !redToMove
            }
            previous = cells
        } else if (cells != before) {
            val path = BoardTransitionResolver.resolve(before, cells, redToMove, turnKnown)
                ?: return Observation(redToMove, lastMove, false)
            val last = path.last()
            val board = last.position.play(last.move)
            lastMove = last
            redToMove = board.redToMove
            turnKnown = true
            previous = cells
        }
        return Observation(redToMove, lastMove, true)
    }
}
