package dev.xiangqi.assistant.recognition

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import dev.xiangqi.assistant.domain.board.BoardPosition
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** On-device board pose and piece inference. */
class BoardRecognizer(context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val pose: OrtSession
    private val layout: OrtSession
    private val owned = ArrayDeque<AutoCloseable>()
    private val poseInput: ModelInput
    private val layoutInput: ModelInput
    private val poseFeeds: Map<String, OnnxTensor>
    private val layoutFeeds: Map<String, OnnxTensor>
    private val corners = FloatArray(8)
    private val affine = Matrix()
    private val warp = Matrix()
    private var cachedBoard: RecognizedBoard? = null
    private var cachedWidth = 0
    private var cachedHeight = 0
    internal var inferredFrames = 0
        private set
    internal var reusedFrames = 0
        private set
    private var closed = false

    init {
        try {
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(1)
                options.setInterOpNumThreads(1)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                pose = own(environment.createSession(context.assets.open("models/board-pose.onnx").use { it.readBytes() }, options))
                layout = own(environment.createSession(context.assets.open("models/board-layout.onnx").use { it.readBytes() }, options))
            }
            poseInput = own(ModelInput(environment, 256, 256))
            layoutInput = own(ModelInput(environment, 280, 315))
            poseFeeds = mapOf(pose.inputNames.first() to poseInput.tensor)
            layoutFeeds = mapOf(layout.inputNames.first() to layoutInput.tensor)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun <T : AutoCloseable> own(resource: T): T = resource.also { owned.addFirst(it) }

    fun recognize(image: Bitmap, redToMove: Boolean, validateTurn: Boolean = true): BoardPosition =
        recognizeLocated(image, redToMove, validateTurn).position

    fun recognizeLocated(image: Bitmap, redToMove: Boolean, validateTurn: Boolean = true): RecognizedBoard {
        check(!closed) { "识别模型已关闭" }
        val cached = cachedBoard
        if (cached != null && image.width == cachedWidth && image.height == cachedHeight) {
            // Reuse only identical board-input pixels.
            layoutInput.render(image, warp)
            if (layoutInput.matchesRememberedPixels()) {
                val position = cached.position.copy(redToMove = redToMove)
                position.validate(validateTurn)
                reusedFrames++
                return cached.copy(position = position)
            }
        }
        // Invalidate before changing the warp.
        cachedBoard = null
        val extent = max(image.width, image.height) * 1.25f
        val scale = 256f / extent
        val left = image.width / 2f - extent / 2f
        val top = image.height / 2f - extent / 2f
        affine.setValues(floatArrayOf(scale, 0f, -left * scale, 0f, scale, -top * scale, 0f, 0f, 1f))
        poseInput.render(image, affine)
        poseInput.prepareTensor()
        pose.run(poseFeeds).use { outputs ->
            @Suppress("UNCHECKED_CAST")
            val xs = (outputs[0].value as Array<Array<FloatArray>>)[0]
            @Suppress("UNCHECKED_CAST")
            val ys = (outputs[1].value as Array<Array<FloatArray>>)[0]
            require(xs.size == 4 && ys.size == 4) { "棋盘定位模型输出尺寸异常" }
            for (i in 0..3) {
                val xi = xs[i].indices.maxBy { xs[i][it] }
                val yi = ys[i].indices.maxBy { ys[i][it] }
                require(xs[i][xi] > 0.15f && ys[i][yi] > 0.15f) { "未找到清晰棋盘，请切换到棋盘画面" }
                corners[i * 2] = left + xi / 2f / scale
                corners[i * 2 + 1] = top + yi / 2f / scale
            }
        }
        // Model corners may follow either player orientation.
        fun distance(a: Int, b: Int) = hypot(corners[a * 2] - corners[b * 2], corners[a * 2 + 1] - corners[b * 2 + 1])
        val width = (distance(0, 1) + distance(2, 3)) / 2f
        val height = (distance(0, 2) + distance(1, 3)) / 2f
        require(width > image.width * 0.15f && height > image.height * 0.15f && width / height in 0.35f..1.8f) {
            "未找到完整棋盘，请保持九路十行可见"
        }
        require(corners.indices.all { i -> corners[i] in -8f..(if (i % 2 == 0) image.width + 8f else image.height + 8f) }) {
            "棋盘超出画面，请显示完整棋盘"
        }
        // Warp, center-crop, then resize to the 280-by-315 input.
        require(warp.setPolyToPoly(corners, 0, floatArrayOf(17.5f, 17.5f, 262.5f, 17.5f, 17.5f, 297.5f, 262.5f, 297.5f), 0, 4)) {
            "棋盘定位不稳定，正在重新识别"
        }
        layoutInput.render(image, warp)
        layoutInput.prepareTensor()
        inferredFrames++
        layout.run(layoutFeeds).use { outputs ->
            @Suppress("UNCHECKED_CAST")
            val cells = (outputs[0].value as Array<Array<FloatArray>>)[0]
            require(cells.size == 90 && cells.all { it.size == 16 }) { "棋子识别模型输出尺寸异常" }
            val labels = ".xKABNRCPkabnrcp"
            var confidence = 1f
            val pieces = CharArray(90) { cell ->
                val scores = cells[cell]
                val index = scores.indices.maxBy { scores[it] }
                confidence = min(confidence, scores[index])
                labels[index]
            }
            require(confidence >= 0.60f) { "棋子不够清晰或被遮挡，正在重新识别" }
            val raw = String(pieces)
            val board = BoardPosition.fromOrientedCells(raw, redToMove, validateTurn)
            val points = (0..3).map { BoardPoint(corners[it * 2] / image.width, corners[it * 2 + 1] / image.height) }
            return RecognizedBoard(board, BoardGeometry(if (board.cells == raw) points else points.reversed())).also {
                layoutInput.rememberPixels()
                cachedWidth = image.width
                cachedHeight = image.height
                cachedBoard = it
            }
        }
    }

    /** Worker-owned image and tensor buffers. */
    private class ModelInput(environment: OrtEnvironment, width: Int, height: Int) : AutoCloseable {
        private val size = width * height
        private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        private val canvas = Canvas(bitmap)
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val pixels = IntArray(size)
        private var rememberedPixels: IntArray? = null
        private val channels = FloatArray(size * 3)
        private val values = ByteBuffer.allocateDirect(size * 3 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val tensor = try {
            OnnxTensor.createTensor(environment, values, longArrayOf(1, 3, height.toLong(), width.toLong()))
        } catch (error: Throwable) { bitmap.recycle(); throw error }

        fun render(image: Bitmap, transform: Matrix) {
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(image, transform, paint)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

        fun matchesRememberedPixels() = rememberedPixels?.contentEquals(pixels) == true

        fun rememberPixels() {
            val previous = rememberedPixels
            if (previous == null) rememberedPixels = pixels.copyOf() else pixels.copyInto(previous)
        }

        fun prepareTensor() {
            for (i in pixels.indices) {
                val pixel = pixels[i]
                channels[i] = RED[pixel shr 16 and 255]
                channels[size + i] = GREEN[pixel shr 8 and 255]
                channels[size * 2 + i] = BLUE[pixel and 255]
            }
            // Write channels in one bulk copy.
            values.clear()
            values.put(channels)
            values.rewind()
        }

        override fun close() { try { tensor.close() } finally { bitmap.recycle() } }

        companion object {
            private val RED = FloatArray(256) { (it - 123.675f) / 58.395f }
            private val GREEN = FloatArray(256) { (it - 116.28f) / 57.12f }
            private val BLUE = FloatArray(256) { (it - 103.53f) / 57.375f }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        cachedBoard = null
        while (owned.isNotEmpty()) {
            try { owned.removeFirst().close() }
            catch (error: Exception) { Log.w("XiangqiAssistant", "Unable to release recognition resource", error) }
        }
    }
}
