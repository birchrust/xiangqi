package dev.xiangqi.assistant.engine

import android.content.Context
import android.util.Log
import dev.xiangqi.assistant.EngineSettings
import dev.xiangqi.assistant.application.analysis.AnalysisEngine
import dev.xiangqi.assistant.domain.board.BoardPosition
import dev.xiangqi.assistant.engine.model.EngineAnswer
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Persistent UCI process with a bounded output queue. */
class PikafishEngine(private val context: Context) : AnalysisEngine {
    private val lifecycle = Any()
    @Volatile private var process: Process? = null
    @Volatile private var closed = false
    private var writer: BufferedWriter? = null
    private val lines = LinkedBlockingQueue<String>(256)

    private fun start() {
        check(!closed) { "计算已停止" }
        if (process?.let { isRunning(it) } == true) return
        val binary = File(context.applicationInfo.nativeLibraryDir, "libpikafish.so")
        check(binary.canExecute()) { "当前设备缺少 Pikafish 引擎，请重新构建对应 ABI 的安装包" }
        val directory = File(context.filesDir, "pikafish").apply { mkdirs() }
        val network = File(directory, "pikafish-2026-09-06.nnue")
        if (network.length() != NETWORK_SIZE) {
            val temporary = File(directory, "network.tmp")
            context.assets.open("pikafish/pikafish.nnue").use { input -> temporary.outputStream().use { input.copyTo(it) } }
            check(temporary.length() == NETWORK_SIZE && temporary.renameTo(network)) { "Pikafish 权重文件准备失败" }
        }
        check(!closed) { "计算已停止" }
        synchronized(lifecycle) { check(!closed) { "计算已停止" }; lines.clear() }
        val child = ProcessBuilder(binary.absolutePath).directory(directory).redirectErrorStream(true).start()
        synchronized(lifecycle) {
            // Register atomically so close cannot orphan the child.
            if (closed) { child.destroy(); error("计算已停止") }
            process = child
            writer = child.outputStream.bufferedWriter()
        }
        Thread({
            try {
                child.inputStream.bufferedReader().useLines { stream ->
                    stream.forEach { line ->
                        synchronized(lifecycle) {
                            if (process === child) offerLine(line)
                        }
                    }
                }
            } catch (_: Exception) { /* Closed during shutdown. */ }
            finally { synchronized(lifecycle) { if (process === child) offerLine(ENGINE_EXITED) } }
        }, "pikafish-uci-reader").apply { isDaemon = true; start() }
        send("uci")
        await(10_000) { it == "uciok" }
        send("setoption name Threads value 1")
        send("setoption name Hash value 16")
        send("setoption name MultiPV value 4")
        send("setoption name EvalFile value ${network.absolutePath}")
        send("isready")
        await(15_000) { it == "readyok" }
    }

    fun bestMove(position: BoardPosition, maxDepth: Int = EngineSettings.maxDepth(context)): EngineAnswer {
        var answer: EngineAnswer? = null
        analyze(position, maxDepth, { answer = it }, { false })
        return checkNotNull(answer) { "Pikafish 未返回结果" }
    }

    override fun analyze(position: BoardPosition, maxDepth: Int,
                         onUpdate: (EngineAnswer) -> Unit, cancelled: () -> Boolean) {
        require(maxDepth in 1..128)
        position.validate()
        if (cancelled()) return
        try {
            start()
            if (cancelled()) return
            send("position fen ${position.fen()}")
            Log.i("XiangqiAssistant", "Search started: ${position.fen()} depth $maxDepth")
            send("go depth $maxDepth")
            val analysis = UciAnalysis()
            var lastAnswer: EngineAnswer? = null
            var stoppedAt = 0L
            while (!closed) {
                if (cancelled() && stoppedAt == 0L) {
                    send("stop")
                    stoppedAt = System.nanoTime()
                }
                check(stoppedAt == 0L || System.nanoTime() - stoppedAt < TimeUnit.SECONDS.toNanos(3)) {
                    "Pikafish 停止搜索超时，请重新开启分析"
                }
                val line = lines.poll(100, TimeUnit.MILLISECONDS) ?: continue
                check(line != ENGINE_EXITED) { "Pikafish 意外退出，请重试" }
                if (line.startsWith("bestmove ")) {
                    if (stoppedAt == 0L && !cancelled()) {
                        val best = line.split(' ').getOrElse(1) { "(none)" }
                        val answer = EngineAnswer(best, analysis.finish(best))
                        if (answer != lastAnswer) onUpdate(answer)
                    }
                    return
                }
                if (stoppedAt == 0L && !cancelled() && analysis.accept(line)) {
                    val candidates = analysis.current()
                    if (candidates.isNotEmpty() && candidates.first().depth > (lastAnswer?.candidates?.firstOrNull()?.depth ?: 0)) {
                        val answer = EngineAnswer(candidates.first().moves.first(), candidates)
                        onUpdate(answer)
                        lastAnswer = answer
                    }
                }
            }
            error("计算已停止")
        } catch (error: Exception) {
            releaseProcess(permanent = false)
            throw error
        }
    }

    private fun send(command: String) {
        val sink = synchronized(lifecycle) {
            check(!closed) { "计算已停止" }
            checkNotNull(writer) { "Pikafish 尚未就绪" }
        }
        // Keep pipe I/O outside the lock so close can proceed.
        sink.write(command); sink.newLine(); sink.flush()
    }

    private fun isRunning(child: Process): Boolean = try { child.exitValue(); false } catch (_: IllegalThreadStateException) { true }

    private fun await(timeoutMs: Long, accept: (String) -> Boolean): String {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!closed) {
            val remaining = deadline - System.nanoTime()
            check(remaining > 0) { "Pikafish 响应超时，请关闭后重新开启识别" }
            val line = lines.poll(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(250)), TimeUnit.NANOSECONDS) ?: continue
            check(line != ENGINE_EXITED) { "Pikafish 意外退出，请重试" }
            if (accept(line)) return line
        }
        error("计算已停止")
    }

    private fun offerLine(line: String) {
        if (!lines.offer(line)) { lines.poll(); lines.offer(line) }
    }

    private fun releaseProcess(permanent: Boolean) {
        val child = synchronized(lifecycle) {
            if (permanent && closed) return
            if (permanent) closed = true
            val child = process
            process = null
            writer = null
            offerLine(ENGINE_EXITED)
            child
        }
        child?.destroy()
    }

    override fun close() = releaseProcess(permanent = true)

    companion object {
        private const val NETWORK_SIZE = 50_706_378L
        private const val ENGINE_EXITED = "<pikafish-exited>"
    }
}
