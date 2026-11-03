package dev.xiangqi.assistant.domain.board

/** Zero-based squares on the canonical 10-by-9 board. */
data class BoardMove(val from: Int, val to: Int) {
    init {
        require(from in 0 until BoardPosition.SIZE && to in 0 until BoardPosition.SIZE)
    }
}
