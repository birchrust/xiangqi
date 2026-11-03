package dev.xiangqi.assistant.application.analysis

import dev.xiangqi.assistant.domain.board.BoardPosition
import dev.xiangqi.assistant.engine.model.EngineAnswer

interface AnalysisEngine : AutoCloseable {
    fun analyze(position: BoardPosition, maxDepth: Int, onUpdate: (EngineAnswer) -> Unit, cancelled: () -> Boolean)
}
