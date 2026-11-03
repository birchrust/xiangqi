package dev.xiangqi.assistant.domain.tracking

import dev.xiangqi.assistant.domain.board.BoardMove
import dev.xiangqi.assistant.domain.board.BoardPosition

/** Recovers at most two legal, alternating moves between samples. */
internal object BoardTransitionResolver {
    fun resolve(before: String, after: String, redToMove: Boolean, turnKnown: Boolean): List<ObservedMove>? {
        singleMove(before, after)?.let {
            // The first move may correct the manual turn seed.
            return if (!turnKnown || it.red == redToMove) listOf(it) else null
        }
        val changed = before.indices.filter { before[it] != after[it] }
        if (changed.size !in 2..4) return null
        for (firstRed in if (turnKnown) listOf(redToMove) else listOf(redToMove, !redToMove)) {
            val board = BoardPosition(before, firstRed)
            for (from in changed.filter { before[it] != '.' && before[it].isUpperCase() == firstRed }) {
                for (to in changed) {
                    if (!board.isLegalMove(from, to)) continue
                    val first = ObservedMove(board, BoardMove(from, to))
                    val middle = board.play(first.move)
                    val second = singleMove(middle.cells, after) ?: continue
                    if (second.red != firstRed) return listOf(first, second)
                }
            }
        }
        return null
    }

    private fun singleMove(before: String, after: String): ObservedMove? {
        val changed = before.indices.filter { before[it] != after[it] }
        if (changed.size != 2) return null
        val from = changed.singleOrNull { before[it] != '.' && after[it] == '.' } ?: return null
        val to = changed.singleOrNull { after[it] == before[from] } ?: return null
        val position = BoardPosition(before, before[from].isUpperCase())
        return if (position.isLegalMove(from, to)) ObservedMove(position, BoardMove(from, to)) else null
    }
}
