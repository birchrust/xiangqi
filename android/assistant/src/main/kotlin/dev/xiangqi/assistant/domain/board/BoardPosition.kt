package dev.xiangqi.assistant.domain.board

/** Row-major board with black at the top and red at the bottom. */
data class BoardPosition(val cells: String, val redToMove: Boolean) {
    init {
        require(cells.length == SIZE) { "棋盘尺寸异常" }
    }

    fun validate(checkTurn: Boolean = true) = BoardRules(this).validate(checkTurn)

    fun isLegalMove(from: Int, to: Int): Boolean = BoardRules(this).isLegalMove(from, to)

    fun play(move: BoardMove): BoardPosition {
        require(isLegalMove(move.from, move.to)) { "引擎变化与当前局面不符，正在重新计算" }
        val next = cells.toCharArray().apply {
            this[move.to] = this[move.from]
            this[move.from] = '.'
        }
        return BoardPosition(String(next), !redToMove)
    }

    companion object {
        const val SIZE = 90

        fun fromOrientedCells(cells: String, redToMove: Boolean, validateTurn: Boolean = true): BoardPosition {
            require(cells.length == SIZE) { "棋盘尺寸异常" }
            // Normalize either board orientation.
            val canonical = if (cells.indexOf('K') in 0..44 && cells.indexOf('k') in 45..89) cells.reversed() else cells
            return BoardPosition(canonical, redToMove).also { it.validate(validateTurn) }
        }
    }
}
