package dev.xiangqi.assistant.presentation

import dev.xiangqi.assistant.domain.tracking.Observation
import dev.xiangqi.assistant.engine.UciNotation

val Observation.message: String
    get() = if (ready) "观察中 · ${if (redToMove) "红方" else "黑方"}走" else "等待棋盘稳定…"

val Observation.lastMoveText: String?
    get() = lastMove?.let { observed ->
        val side = if (observed.red) "红方" else "黑方"
        "$side ${observed.position.describeMove(UciNotation.formatMove(observed.move))}"
    }
