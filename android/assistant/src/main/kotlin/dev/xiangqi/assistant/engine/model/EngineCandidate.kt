package dev.xiangqi.assistant.engine.model

data class EngineCandidate(val rank: Int, val moves: List<String>, val depth: Int, val score: UciScore?)
