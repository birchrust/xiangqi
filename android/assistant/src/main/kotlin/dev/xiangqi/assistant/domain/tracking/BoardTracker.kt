package dev.xiangqi.assistant.domain.tracking

import dev.xiangqi.assistant.domain.board.BoardPosition

/** Infer the turn before validating a stable position. */
class BoardTracker(initialRedToMove: Boolean) {
    private val stable = StableBoardFilter()
    private val observer = MoveObserver(initialRedToMove)
    private var accepted: TrackedBoard? = null

    fun accept(cells: String): TrackedBoard? {
        if (!stable.accept(cells)) return null
        accepted?.let { if (it.position.cells == cells) return it }
        val observation = observer.accept(cells)
        if (!observation.ready) return null
        val position = BoardPosition(cells, observation.redToMove)
        position.validate()
        stable.confirm(cells)
        return TrackedBoard(position, observation).also { accepted = it }
    }

    fun miss() = stable.miss()
}
