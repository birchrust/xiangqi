package dev.xiangqi.assistant.engine

import dev.xiangqi.assistant.domain.board.BoardMove
import dev.xiangqi.assistant.domain.board.BoardPosition

/** UCI coordinates use a0 at the red player's left corner. */
object UciNotation {
    private val MOVE = Regex("[a-i][0-9][a-i][0-9]")

    fun isMove(value: String): Boolean = MOVE.matches(value)

    fun parseMove(value: String): BoardMove {
        require(isMove(value)) { "引擎返回了无法识别的招法" }
        fun square(offset: Int) = (9 - (value[offset + 1] - '0')) * 9 + (value[offset] - 'a')
        return BoardMove(square(0), square(2))
    }

    fun formatMove(move: BoardMove): String {
        fun square(index: Int) = "${('a'.code + index % 9).toChar()}${9 - index / 9}"
        return square(move.from) + square(move.to)
    }
}

fun BoardPosition.play(move: String): BoardPosition = play(UciNotation.parseMove(move))

fun BoardPosition.fen(): String {
    val rows = cells.chunked(9).map { row ->
        buildString {
            var empty = 0
            for (piece in row) {
                if (piece == '.') empty++ else {
                    if (empty > 0) append(empty)
                    empty = 0
                    append(piece)
                }
            }
            if (empty > 0) append(empty)
        }
    }
    return rows.joinToString("/") + if (redToMove) " w - - 0 1" else " b - - 0 1"
}
