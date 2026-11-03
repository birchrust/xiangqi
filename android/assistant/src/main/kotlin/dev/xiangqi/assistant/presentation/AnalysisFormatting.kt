package dev.xiangqi.assistant.presentation

import dev.xiangqi.assistant.domain.board.BoardPosition
import dev.xiangqi.assistant.engine.model.EngineAnswer
import dev.xiangqi.assistant.engine.model.UciScore
import java.util.Locale
import kotlin.math.abs

val EngineAnswer.detail: String
    get() = candidates.firstOrNull()?.let { "深度 ${it.depth}" } ?: "无合法招法"

fun EngineAnswer.display(position: BoardPosition, index: Int = 0): List<DisplayMove> =
    if (move == "(none)" || move == "0000") listOf(DisplayMove("无合法招法", position.redToMove))
    else position.describeVariation(candidates.getOrNull(index)?.moves ?: emptyList())

fun EngineAnswer.redScore(position: BoardPosition, index: Int = 0): String =
    candidates.getOrNull(index)?.score?.redText(position.redToMove) ?: "—"

fun UciScore.redText(redToMove: Boolean): String {
    val redValue = if (redToMove) value else -value
    return if (type == "mate") "${if (redValue >= 0) "+" else "−"}M${abs(redValue)}"
        else String.format(Locale.ROOT, "%+.2f", redValue / 100.0)
}
