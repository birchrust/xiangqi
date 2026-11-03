package dev.xiangqi.assistant.presentation

import dev.xiangqi.assistant.domain.board.BoardPosition
import dev.xiangqi.assistant.engine.UciNotation
import dev.xiangqi.assistant.engine.play
import kotlin.math.abs

fun BoardPosition.describeMove(move: String): String {
    if (move == "(none)" || move == "0000") return "无合法招法（将死或困毙）"
    val parsed = UciNotation.parseMove(move)
    val fromCol = parsed.from % 9
    val fromRow = parsed.from / 9
    val toCol = parsed.to % 9
    val toRow = parsed.to / 9
    val piece = cells[fromRow * 9 + fromCol]
    require(piece != '.' && piece.isUpperCase() == redToMove) { "招法与当前局面不符，正在重新识别" }
    val red = piece.isUpperCase()
    fun number(value: Int) = if (red) "一二三四五六七八九"[value - 1].toString() else value.toString()
    fun file(col: Int) = number(if (red) 9 - col else col + 1)
    val names = mapOf('K' to "帅", 'A' to "仕", 'B' to "相", 'N' to "马", 'R' to "车", 'C' to "炮", 'P' to "兵",
        'k' to "将", 'a' to "士", 'b' to "象", 'n' to "马", 'r' to "车", 'c' to "炮", 'p' to "卒")
    val sameFile = (0..9).filter { cells[it * 9 + fromCol] == piece }.sortedBy { if (red) it else -it }
    val prefix = if (sameFile.size == 2) {
        (if (sameFile.first() == fromRow) "前" else "后") + names.getValue(piece)
    } else names.getValue(piece) + file(fromCol)
    val direction = when {
        fromRow == toRow -> "平"
        (toRow < fromRow) == red -> "进"
        else -> "退"
    }
    val destination = if (fromRow == toRow || piece.uppercaseChar() in "NAB") file(toCol) else number(abs(toRow - fromRow))
    return "$prefix$direction$destination（$move）"
}

fun BoardPosition.describeVariation(moves: List<String>): List<DisplayMove> {
    var board = this
    return moves.map { move ->
        val text = board.describeMove(move).substringBefore('（')
        val display = DisplayMove(text, board.redToMove)
        board = board.play(move)
        display
    }
}
