package dev.xiangqi.assistant.engine

import dev.xiangqi.assistant.engine.model.EngineCandidate
import dev.xiangqi.assistant.engine.model.UciScore

/** Publishes complete MultiPV iterations with distinct moves. */
class UciAnalysis {
    private val depths = sortedMapOf<Int, MutableMap<Int, EngineCandidate>>()
    private val latest = mutableMapOf<String, EngineCandidate>()
    private var maxRank = 1
    private var complete = emptyList<EngineCandidate>()

    fun accept(line: String): Boolean {
        if (!line.startsWith("info ") || line.contains(" lowerbound") || line.contains(" upperbound")) return false
        val pv = PV.find(line) ?: return false
        val moves = pv.groupValues[1].trim().split(WHITESPACE)
            .takeWhile { UciNotation.isMove(it) }
        if (moves.isEmpty()) return false
        val rank = RANK.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        if (rank !in 1..4) return false
        val depth = DEPTH.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val score = SCORE.find(line)?.let { UciScore(it.groupValues[1], it.groupValues[2].toInt()) }
        val candidate = EngineCandidate(rank, moves, depth, score)
        val batch = depths.getOrPut(depth) { mutableMapOf() }
        if (batch[rank] == candidate) return false
        batch[rank] = candidate
        latest[moves.first()] = candidate
        maxRank = maxOf(maxRank, rank)
        if (batch.size == 4 && depth >= (complete.firstOrNull()?.depth ?: 0) &&
            batch.values.map { it.moves.first() }.distinct().size == 4) complete = batch.values.sortedBy { it.rank }
        return true
    }

    fun finish(best: String): List<EngineCandidate> {
        if (best == "(none)" || best == "0000") return emptyList()
        val finished = depths.values.reversed().firstOrNull { batch ->
            (1..maxRank).all { it in batch } && batch[1]?.moves?.first() == best &&
                batch.values.map { it.moves.first() }.distinct().size == batch.size
        }
        if (finished != null) return finished.values.sortedBy { it.rank }.take(4)
        // Prefer bestmove when the final iteration is incomplete.
        val first = latest[best] ?: EngineCandidate(1, listOf(best), 0, null)
        return (listOf(first) + latest.values.sortedWith(compareBy<EngineCandidate> { it.rank }.thenByDescending { it.depth }))
            .distinctBy { it.moves.first() }.take(4).mapIndexed { index, value -> value.copy(rank = index + 1) }
    }

    /** Keep partial iterations off the UI. */
    fun current(): List<EngineCandidate> = complete

    private companion object {
        val PV = Regex("\\bpv (.+)$")
        val WHITESPACE = Regex("\\s+")
        val RANK = Regex("\\bmultipv (\\d+)")
        val DEPTH = Regex("\\bdepth (\\d+)")
        val SCORE = Regex("\\bscore (cp|mate) (-?\\d+)")
    }
}
