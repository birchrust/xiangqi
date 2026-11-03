package dev.xiangqi.assistant

import android.content.Context

object EngineSettings {
    const val MAX_DEPTH = "max_depth"
    const val DEFAULT_DEPTH = 20
    const val DEPTH_RANGE = "1–128"
    fun preferences(context: Context) = context.getSharedPreferences("engine", Context.MODE_PRIVATE)
    fun maxDepth(context: Context) = preferences(context).getInt(MAX_DEPTH, DEFAULT_DEPTH).coerceIn(1, 128)
    fun setMaxDepth(context: Context, depth: Int) {
        require(depth in 1..128)
        preferences(context).edit().putInt(MAX_DEPTH, depth).apply()
    }
}
