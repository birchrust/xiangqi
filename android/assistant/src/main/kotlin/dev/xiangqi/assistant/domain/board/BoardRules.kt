package dev.xiangqi.assistant.domain.board

import kotlin.math.abs

/** Xiangqi legality for a canonical position. */
internal class BoardRules(private val position: BoardPosition) {
    private val cells get() = position.cells
    private val redToMove get() = position.redToMove

    fun validate(checkTurn: Boolean = true) {
        require(cells.all { it in ".KABNRCPkabnrcp" }) { "棋盘有遮挡或未知棋子，请保持棋盘完整可见" }
        require(cells.count { it == 'K' } == 1 && cells.count { it == 'k' } == 1) { "未识别到完整的双方将帅" }
        for ((piece, limit) in PIECE_LIMITS) {
            require(cells.count { it == piece } <= limit && cells.count { it == piece.lowercaseChar() } <= limit) { "棋子数量异常，正在重新识别" }
        }
        for ((index, piece) in cells.withIndex()) {
            val row = index / 9
            val col = index % 9
            val homeRow = if (piece.isUpperCase()) 9 - row else row
            when (piece.uppercaseChar()) {
                'K' -> require(col in 3..5 && homeRow in 0..2) { "将帅位置异常，正在重新识别" }
                'A' -> require((homeRow to col) in ADVISOR_SQUARES) { "士的位置异常，正在重新识别" }
                'B' -> require((homeRow to col) in ELEPHANT_SQUARES) { "象的位置异常，正在重新识别" }
                'P' -> require(homeRow >= 3 && (homeRow >= 5 || col % 2 == 0)) { "兵卒位置异常，正在重新识别" }
            }
        }
        val redKing = cells.indexOf('K')
        val blackKing = cells.indexOf('k')
        require(redKing % 9 != blackKing % 9 || between(redKing, blackKing) > 0) { "将帅照面，正在重新识别" }
        // The previous mover cannot remain in check.
        val otherKing = if (redToMove) blackKing else redKing
        require(!checkTurn || !attacked(otherKing, byRed = redToMove)) { "行棋方与将军局面不符，请切换红方／黑方或等待画面稳定" }
    }

    fun isLegalMove(from: Int, to: Int): Boolean {
        if (from !in 0..89 || to !in 0..89 || from == to) return false
        val piece = cells[from]
        val target = cells[to]
        if (piece == '.' || piece.isUpperCase() != redToMove || target.uppercaseChar() == 'K') return false
        if (target != '.' && target.isUpperCase() == redToMove) return false
        val r = from / 9
        val c = from % 9
        val row = to / 9
        val col = to % 9
        val dr = row - r
        val dc = col - c
        val homeRow = if (redToMove) 9 - row else row
        val reachable = when (piece.uppercaseChar()) {
            'R' -> (dr == 0 || dc == 0) && between(from, to) == 0
            'C' -> (dr == 0 || dc == 0) && between(from, to) == if (target == '.') 0 else 1
            'N' -> knightReachable(r, c, dr, dc)
            'B' -> abs(dr) == 2 && abs(dc) == 2 && homeRow <= 4 && cells[(r + dr / 2) * 9 + c + dc / 2] == '.'
            'A' -> abs(dr) == 1 && abs(dc) == 1 && homeRow in 0..2 && col in 3..5
            'K' -> abs(dr) + abs(dc) == 1 && homeRow in 0..2 && col in 3..5
            'P' -> (dc == 0 && dr == if (redToMove) -1 else 1) ||
                (dr == 0 && abs(dc) == 1 && if (redToMove) r <= 4 else r >= 5)
            else -> false
        }
        if (!reachable) return false
        return try {
            validate()
            val next = cells.toCharArray().apply {
                this[from] = '.'
                this[to] = piece
            }
            BoardPosition(String(next), !redToMove).validate()
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun between(from: Int, to: Int): Int {
        val step = if (from / 9 == to / 9) (if (to > from) 1 else -1) else (if (to > from) 9 else -9)
        var index = from + step
        var count = 0
        while (index != to) {
            if (cells[index] != '.') count++
            index += step
        }
        return count
    }

    private fun attacked(target: Int, byRed: Boolean): Boolean {
        val row = target / 9
        val col = target % 9
        return cells.withIndex().any { (index, piece) ->
            if (piece == '.' || piece.isUpperCase() != byRed) return@any false
            val r = index / 9
            val c = index % 9
            val dr = row - r
            val dc = col - c
            when (piece.uppercaseChar()) {
                'R' -> (dr == 0 || dc == 0) && between(index, target) == 0
                'C' -> (dr == 0 || dc == 0) && between(index, target) == 1
                'N' -> knightReachable(r, c, dr, dc)
                'P' -> (dc == 0 && dr == if (byRed) -1 else 1) ||
                    (dr == 0 && abs(dc) == 1 && if (byRed) r <= 4 else r >= 5)
                'K' -> abs(dr) + abs(dc) == 1
                'A' -> abs(dr) == 1 && abs(dc) == 1
                'B' -> abs(dr) == 2 && abs(dc) == 2 && cells[(r + dr / 2) * 9 + c + dc / 2] == '.'
                else -> false
            }
        }
    }

    private fun knightReachable(row: Int, col: Int, dr: Int, dc: Int): Boolean = when {
        abs(dr) == 2 && abs(dc) == 1 -> cells[(row + dr / 2) * 9 + col] == '.'
        abs(dr) == 1 && abs(dc) == 2 -> cells[row * 9 + col + dc / 2] == '.'
        else -> false
    }

    private companion object {
        val PIECE_LIMITS = mapOf('K' to 1, 'A' to 2, 'B' to 2, 'N' to 2, 'R' to 2, 'C' to 2, 'P' to 5)
        val ADVISOR_SQUARES = setOf(0 to 3, 0 to 5, 1 to 4, 2 to 3, 2 to 5)
        val ELEPHANT_SQUARES = setOf(0 to 2, 0 to 6, 2 to 0, 2 to 4, 2 to 8, 4 to 2, 4 to 6)
    }
}
