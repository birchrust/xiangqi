package dev.xiangqi.assistant.application.analysis

import dev.xiangqi.assistant.domain.board.BoardPosition
import dev.xiangqi.assistant.engine.model.EngineAnswer
import java.util.concurrent.Executors

/** Serializes searches and caches complete iterations. */
class AnalysisCoordinator(private val createEngine: () -> AnalysisEngine) : AutoCloseable {
    private data class Key(val position: BoardPosition, val depth: Int)
    private class Request(
        val id: Long, val key: Key,
        val update: (Long, EngineAnswer) -> Unit, val error: (Long, Exception) -> Unit,
        var lastAnswer: EngineAnswer? = null, var failed: Boolean = false
    )
    private class Job(val key: Key, var cancelled: Boolean = false, var lastAnswer: EngineAnswer? = null)

    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "xiangqi-engine") }
    private val cache = object : LinkedHashMap<Key, EngineAnswer>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, EngineAnswer>?) = size > 64
    }
    private var sequence = 0L
    private var desired: Request? = null
    private var active: Job? = null
    private var running = false
    private var closed = false
    private var engine: AnalysisEngine? = null

    /** Reuse cached results before starting a search. */
    fun request(position: BoardPosition, depth: Int,
                onUpdate: (Long, EngineAnswer) -> Unit, onError: (Long, Exception) -> Unit): Boolean {
        require(depth in 1..128)
        synchronized(lock) {
            val key = Key(position, depth)
            if (closed || desired?.key == key) return false
            val previous = desired
            desired = Request(++sequence, key, onUpdate, onError,
                previous?.takeIf { it.key.position == position }?.lastAnswer?.takeIf { answerDepth(it) <= depth })
            if (!running) {
                running = true
                worker.execute { runLatest() }
            }
        }
        return true
    }

    fun isCurrent(id: Long): Boolean = synchronized(lock) { !closed && desired?.id == id }

    private fun runLatest() {
        while (true) {
            var cached: Pair<Request, EngineAnswer>? = null
            val job = synchronized(lock) {
                val request = desired
                if (closed || request == null || request.failed) { running = false; return }
                val answer = cache[request.key]
                if (answer != null) {
                    cached = delivery(request, answer)
                    running = false
                    null
                } else Job(request.key).also { active = it }
            }
            if (job == null) { cached?.let { deliver(it) }; return }
            try {
                val seed = synchronized(lock) {
                    val request = desired
                    if (request?.key?.position != job.key.position) null else {
                        val key = cache.keys.filter { it.position == job.key.position && it.depth <= request.key.depth }.maxByOrNull { it.depth }
                        key?.let { cache[it] }?.let { delivery(request, it) }
                    }
                }
                seed?.let { deliver(it) }
                val calculator = synchronized(lock) {
                    if (cancelled(job)) null else engine ?: createEngine().also { engine = it }
                } ?: continue
                calculator.analyze(job.key.position, job.key.depth, { answer ->
                    val update = synchronized(lock) {
                        if (job.cancelled || closed) null else {
                            job.lastAnswer = answer
                            val depth = answerDepth(answer)
                            // Cache only complete, uniform iterations.
                            if (depth > 0 && answer.candidates.all { it.depth == depth })
                                cache[Key(job.key.position, depth)] = answer
                            desired?.takeIf { it.key.position == job.key.position && depth <= it.key.depth }
                                ?.let { delivery(it, answer) }
                        }
                    }
                    update?.let { deliver(it) }
                }, { cancelled(job) })
                synchronized(lock) {
                    check(job.cancelled || closed || job.lastAnswer != null) { "Pikafish 未返回结果，请重新开启分析" }
                    // Completed searches satisfy the requested limit.
                    if (!job.cancelled && !closed) job.lastAnswer?.let { answer ->
                        cache[job.key] = answer
                        desired?.takeIf { it.key.position == job.key.position && it.key.depth <= job.key.depth &&
                            answerDepth(answer) <= it.key.depth }?.let { cache[it.key] = answer }
                    }
                }
            } catch (error: Exception) {
                val request = synchronized(lock) {
                    desired?.takeIf { !closed && !job.cancelled && it.key.position == job.key.position }
                        ?.also { it.failed = true }
                }
                request?.let { if (isCurrent(it.id)) it.error(it.id, error) }
            } finally { synchronized(lock) { if (active === job) active = null } }
        }
    }

    private fun cancelled(job: Job): Boolean = synchronized(lock) {
        val request = desired
        if (closed || request == null || request.key.position != job.key.position ||
            (request.key != job.key && cache.containsKey(request.key)) ||
            (request.key.depth < job.key.depth && job.lastAnswer?.let { answerDepth(it) >= request.key.depth } == true)) job.cancelled = true
        job.cancelled
    }

    // Suppress duplicate or shallower updates.
    private fun delivery(request: Request, answer: EngineAnswer): Pair<Request, EngineAnswer>? {
        val last = request.lastAnswer
        if (last == answer || (last != null && answerDepth(last) > answerDepth(answer))) return null
        request.lastAnswer = answer
        return request to answer
    }

    private fun deliver(value: Pair<Request, EngineAnswer>) {
        val (request, answer) = value
        if (isCurrent(request.id)) request.update(request.id, answer)
    }

    fun cancel(releaseEngine: Boolean = false) {
        val old = synchronized(lock) {
            desired = null
            active?.cancelled = true
            if (releaseEngine) engine.also { engine = null } else null
        }
        old?.close()
    }

    override fun close() {
        synchronized(lock) { closed = true; cache.clear() }
        cancel(releaseEngine = true)
        worker.shutdown()
    }

    private fun answerDepth(answer: EngineAnswer) = answer.candidates.maxOfOrNull { it.depth } ?: 0
}
