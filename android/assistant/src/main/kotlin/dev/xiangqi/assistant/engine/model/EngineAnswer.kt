package dev.xiangqi.assistant.engine.model

data class EngineAnswer(val move: String, val candidates: List<EngineCandidate>) {
    val variation: List<String> get() = candidates.firstOrNull()?.moves ?: emptyList()
}
