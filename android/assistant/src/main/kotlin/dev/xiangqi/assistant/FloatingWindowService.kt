package dev.xiangqi.assistant

import android.app.Activity
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.xiangqi.assistant.application.analysis.AnalysisCoordinator
import dev.xiangqi.assistant.capture.CapturedFrame
import dev.xiangqi.assistant.capture.ScreenCapture
import dev.xiangqi.assistant.domain.board.BoardPosition
import dev.xiangqi.assistant.domain.tracking.BoardTracker
import dev.xiangqi.assistant.engine.PikafishEngine
import dev.xiangqi.assistant.presentation.AnalysisResult
import dev.xiangqi.assistant.presentation.DisplayMove
import dev.xiangqi.assistant.presentation.MoveArrowOverlay
import dev.xiangqi.assistant.presentation.MoveSequenceView
import dev.xiangqi.assistant.presentation.detail
import dev.xiangqi.assistant.presentation.display
import dev.xiangqi.assistant.presentation.lastMoveText
import dev.xiangqi.assistant.presentation.message
import dev.xiangqi.assistant.recognition.BoardGeometry
import dev.xiangqi.assistant.recognition.BoardRecognizer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt

class FloatingWindowService : Service() {
    private lateinit var overlayContext: Context
    private lateinit var windowManager: WindowManager
    private lateinit var appOps: AppOpsManager
    private var panel: LinearLayout? = null
    private var windowParameters: WindowManager.LayoutParams? = null
    private lateinit var analysisButton: ImageButton
    private lateinit var observationButton: ImageButton
    private lateinit var turnButton: ImageButton
    private lateinit var thinkingButton: ImageButton
    private lateinit var stopButton: ImageButton
    private lateinit var scoreView: TextView
    private lateinit var candidateButtons: List<Button>
    private lateinit var moveSequence: MoveSequenceView
    private lateinit var arrows: MoveArrowOverlay
    private var recognizedPosition: BoardPosition? = null
    private var boardGeometry: BoardGeometry? = null
    private var stateMessage = "点击分析或观察开始"
    private var selectedCandidate = 0
    private var displayedResult: AnalysisResult? = null
    @Volatile private var analysisEnabled = false
    @Volatile private var observationEnabled = false
    @Volatile private var thinkingEnabled = false
    private var pendingRequest = -1L
    @Volatile private var capture: ScreenCapture? = null
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "xiangqi-analysis") }
    private val generation = AtomicLong(0)
    private val observationRevision = AtomicLong(0)
    private val processing = AtomicBoolean(false)
    @Volatile private var redToMove = false
    @Volatile private var destroyed = false
    private val searches = AnalysisCoordinator { PikafishEngine(applicationContext) }
    // Recognition-worker state.
    private var recognizer: BoardRecognizer? = null
    private var workerGeneration = -1L
    private var boardTracker = BoardTracker(false)
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == EngineSettings.MAX_DEPTH) {
            if (capture != null && hasCalculation()) {
                (recognizedPosition ?: displayedResult?.position)?.let { requestAnalysis(generation.get(), it) }
            }
            if (capture != null && displayedResult == null) setStatus("搜索深度已更新，等待稳定棋盘…")
        }
    }
    private val permissionListener = AppOpsManager.OnOpChangedListener { op, changedPackage ->
        if (op == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && changedPackage == packageName) {
            handler.post { if (!Settings.canDrawOverlays(this)) stopSelf() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        overlayContext = this
        if (Build.VERSION.SDK_INT >= 30) {
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) overlayContext = createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        }
        windowManager = overlayContext.getSystemService(WINDOW_SERVICE) as WindowManager
        arrows = MoveArrowOverlay(overlayContext, windowManager)
        appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, permissionListener)
        EngineSettings.preferences(this).registerOnSharedPreferenceChangeListener(settingsListener)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE || !Settings.canDrawOverlays(this)) {
            stopSelf(); return START_NOT_STICKY
        }
        try {
            foreground(capture != null)
            if (intent?.action == ACTION_CAPTURE_RESULT) {
                // Ignore capture consent after the window closes.
                if (panel == null) stopSelf() else acceptCaptureResult(intent)
            } else if (panel == null) showWindow()
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start floating assistant", error)
            Toast.makeText(this, "悬浮窗启动失败：${error.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun requestCapture() {
        pendingRequest = generation.incrementAndGet()
        updateStopButton()
        setStatus("请在系统提示中允许共享屏幕")
        try {
            startActivity(Intent(this, CapturePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .putExtra(EXTRA_REQUEST_ID, pendingRequest))
        } catch (error: RuntimeException) {
            stopRecognition("无法打开屏幕授权，请返回应用后重试")
            Log.e(TAG, "Capture consent unavailable", error)
        }
    }

    private fun modesChanged(observationChanged: Boolean = false) {
        if (observationChanged) observationRevision.incrementAndGet()
        if (!hasCalculation()) searches.cancel(releaseEngine = !hasActiveMode())
        if (!hasActiveMode()) {
            generation.incrementAndGet()
            clearBoardLocation()
            worker.execute { if (!hasActiveMode()) releaseRecognizer() }
            // Pausing modes retains capture consent.
            setStatus(if (capture != null) "已暂停" else "功能已暂停")
        } else if (capture != null) {
            setStatus(if (observationEnabled) "观察中，等待稳定棋盘…" else if (thinkingEnabled) "后台思考中…" else "分析中，等待稳定棋盘…")
        } else if (pendingRequest == -1L) requestCapture()
    }

    @Suppress("DEPRECATION")
    private fun acceptCaptureResult(intent: Intent) {
        if (intent.getLongExtra(EXTRA_REQUEST_ID, -1) != pendingRequest) return
        pendingRequest = -1
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (code != Activity.RESULT_OK || data == null) {
            stopRecognition("未授权屏幕识别，开关已关闭")
            return
        }
        try {
            // Promote the service before obtaining projection.
            foreground(true)
            generation.incrementAndGet()
            lateinit var screen: ScreenCapture
            screen = ScreenCapture(this, { frame -> submitFrame(frame) }, {
                handler.post { if (capture === screen) stopRecognition("屏幕共享已停止，重新开启需再次授权") }
            }, shouldProcess = { !destroyed && hasActiveMode() && !processing.get() }, prepareFrame = { ready ->
                if (!arrows.hasHints) false else {
                    handler.post { if (!destroyed) arrows.prepareCapture(ready) else ready() }
                    true
                }
            }, frameCaptured = { handler.post { if (!destroyed) arrows.finishCapture() } })
            capture = screen
            screen.start(code, data)
            Log.i(TAG, "Screen sharing started")
            updateStopButton()
            setStatus("识别中，请切换到棋盘画面")
        } catch (error: Exception) {
            stopRecognition("屏幕捕获失败，请重新开启并授权")
            Log.e(TAG, "Unable to capture display", error)
        }
    }

    private fun submitFrame(frame: CapturedFrame) {
        if (destroyed || capture == null || !hasActiveMode() || !processing.compareAndSet(false, true)) { frame.close(); return }
        val token = generation.get()
        val red = redToMove
        try {
            worker.execute {
                try {
                    if (!isCurrent(token)) return@execute
                    if (workerGeneration != token) {
                        boardTracker = BoardTracker(red)
                        workerGeneration = token
                    }
                    val observationToken = observationRevision.get()
                    if (recognizer == null) {
                        publish(token, "正在加载本地识别模型…")
                        recognizer = BoardRecognizer(applicationContext)
                    }
                    if (!isCurrent(token)) return@execute
                    // Infer the turn before validating check legality.
                    val located = recognizer!!.recognizeLocated(frame.bitmap, red, validateTurn = false)
                    if (!isCurrent(token) || observationRevision.get() != observationToken) return@execute
                    val tracked = boardTracker.accept(located.position.cells)
                    if (tracked == null) {
                        hideBoardLocation(token)
                        publish(token, "等待棋盘稳定…")
                        return@execute
                    }
                    if (!isCurrent(token) || observationRevision.get() != observationToken) return@execute
                    val board = tracked.position
                    handler.post {
                        if (isCurrent(token) && observationRevision.get() == observationToken) {
                            if (redToMove != board.redToMove) {
                                redToMove = board.redToMove
                                updateTurnButton()
                            }
                            recognizedPosition = board
                            boardGeometry = located.geometry
                            renderHintArrows()
                        }
                    }
                    if (!hasCalculation()) {
                        val observation = tracked.observation
                        publish(token, observation.message + (observation.lastMoveText?.let { " · 上步：$it" } ?: " · 等待走子"))
                        return@execute
                    }
                    requestAnalysis(token, board)
                } catch (error: Exception) {
                    // Keep confirmed state across transient frames.
                    boardTracker.miss()
                    hideBoardLocation(token)
                    publish(token, error.message ?: "等待清晰棋盘…")
                    Log.d(TAG, "Frame not ready: ${error.message}")
                } finally { frame.close(); processing.set(false) }
            }
        } catch (_: RejectedExecutionException) { frame.close(); processing.set(false) }
    }

    private fun requestAnalysis(token: Long, position: BoardPosition) {
        if (!isCurrent(token) || !hasCalculation()) return
        val depth = EngineSettings.maxDepth(applicationContext)
        val accepted = searches.request(position, depth, { searchId, answer ->
            if (isCurrent(token) && searches.isCurrent(searchId)) {
                val prefix = if (observationEnabled) "观察 · " else if (thinkingEnabled) "后台思考 · " else ""
                val side = if (position.redToMove) "红方" else "黑方"
                val result = AnalysisResult(position, answer)
                publish(token, "$prefix${side}走 · ${answer.detail}", result, searchId)
            }
        }, { searchId, error ->
            publish(token, error.message ?: "引擎计算失败，请重新开启分析", searchId = searchId)
            Log.w(TAG, "Engine search failed", error)
        })
        if (accepted) publish(token, "Pikafish 计算中…")
    }

    private fun publish(token: Long, message: String, result: AnalysisResult? = null, searchId: Long? = null) {
        handler.post {
            if (!isCurrent(token) || (searchId != null && !searches.isCurrent(searchId))) return@post
            if (result != null) {
                displayedResult = result
                selectedCandidate = selectedCandidate.coerceIn(0, (result.candidateCount - 1).coerceAtLeast(0))
                renderSelectedCandidate()
                setStatus(message)
            } else if (displayedResult == null) {
                setStatus(message)
            }
        }
    }

    private fun hasActiveMode() = analysisEnabled || observationEnabled || thinkingEnabled
    private fun hasCalculation() = analysisEnabled || thinkingEnabled
    private fun isCurrent(token: Long) = !destroyed && capture != null && generation.get() == token

    // Close on the inference worker to avoid races.
    private fun releaseRecognizer() {
        recognizer?.close(); recognizer = null
        boardTracker = BoardTracker(redToMove)
        workerGeneration = -1L
    }

    private fun stopRecognition(message: String, updateForeground: Boolean = true) {
        Log.i(TAG, "Screen sharing stopped: $message")
        generation.incrementAndGet(); pendingRequest = -1
        clearBoardLocation()
        capture?.close(); capture = null
        updateStopButton()
        searches.cancel(releaseEngine = true)
        worker.execute { releaseRecognizer() }
        if (::analysisButton.isInitialized) {
            setAnalysisEnabled(false)
            setObservationEnabled(false)
            setThinkingEnabled(false)
            setStatus(message)
        }
        if (updateForeground && !destroyed) foreground(false)
    }

    @Suppress("DEPRECATION")
    private fun foreground(capturing: Boolean) {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "象棋悬浮窗", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, CHANNEL_ID)
        } else Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val open = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val close = PendingIntent.getService(this, 1, Intent(this, FloatingWindowService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = builder.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (capturing) "象棋正在识别屏幕" else "象棋悬浮窗正在运行")
            .setContentText(if (capturing) "屏幕共享已授权；点击浮窗停止按钮结束共享。" else "点击浮窗分析、观察或后台思考开始。")
            .setContentIntent(open).setCategory(Notification.CATEGORY_SERVICE).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "关闭", close).build()).build()
        if (Build.VERSION.SDK_INT >= 34) {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                if (capturing) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
            startForeground(NOTIFICATION_ID, notification, types)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, if (capturing) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    @Suppress("DEPRECATION")
    private fun showWindow() {
        val parameters = WindowManager.LayoutParams(
            (overlayContext.resources.displayMetrics.widthPixels - EDGE * 2).coerceAtLeast(1),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = EDGE; y = EDGE; title = "XiangqiFloatingWindow" }
        val root = LinearLayout(overlayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(8), dp(10))
            background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = 12f; setStroke(dp(1), Color.rgb(64, 64, 64)) }
            elevation = dp(8).toFloat()
        }
        val left = LinearLayout(overlayContext).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(overlayContext).apply { gravity = Gravity.CENTER_VERTICAL }
        analysisButton = ImageButton(overlayContext).apply {
            setImageResource(R.drawable.ic_analyze)
            scaleType = ImageView.ScaleType.CENTER
            setBackgroundResource(R.drawable.bg_analyze)
            setPadding(0, 0, 0, 0)
            contentDescription = "开启屏幕分析"
            setOnClickListener {
                setAnalysisEnabled(!analysisEnabled)
                modesChanged()
            }
        }
        // 36dp icon; 44dp touch target.
        header.addView(analysisButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        observationButton = ImageButton(overlayContext).apply {
            setImageResource(R.drawable.ic_observe)
            scaleType = ImageView.ScaleType.CENTER
            setBackgroundResource(R.drawable.bg_analyze)
            setPadding(0, 0, 0, 0)
            contentDescription = "开启观察模式"
            setOnClickListener {
                setObservationEnabled(!observationEnabled)
                modesChanged(observationChanged = true)
            }
        }
        header.addView(observationButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        turnButton = ImageButton(overlayContext).apply {
            setImageResource(R.drawable.ic_turn)
            scaleType = ImageView.ScaleType.CENTER
            setBackgroundResource(R.drawable.bg_analyze)
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                redToMove = !redToMove
                updateTurnButton()
                generation.incrementAndGet()
                searches.cancel()
                clearBoardLocation()
                clearAnalysis()
                setStatus(if (capture != null && (analysisEnabled || observationEnabled || thinkingEnabled)) "等待稳定棋盘…" else "")
            }
        }
        updateTurnButton()
        header.addView(turnButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        thinkingButton = ImageButton(overlayContext).apply {
            setImageResource(R.drawable.ic_ponder)
            scaleType = ImageView.ScaleType.CENTER
            setBackgroundResource(R.drawable.bg_analyze)
            setPadding(0, 0, 0, 0)
            contentDescription = "开启后台思考"
            setOnClickListener {
                setThinkingEnabled(!thinkingEnabled)
                modesChanged()
            }
        }
        header.addView(thinkingButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        val dragHandle = View(overlayContext).apply { contentDescription = "拖动悬浮窗" }
        header.addView(dragHandle, LinearLayout.LayoutParams(0, dp(44), 1f))
        left.addView(header)
        val selectors = LinearLayout(overlayContext).apply { gravity = Gravity.CENTER_VERTICAL }
        candidateButtons = (0..3).map { index ->
            Button(overlayContext).apply {
                text = "${index + 1}"
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 0)
                minimumWidth = 0; minimumHeight = 0
                setBackgroundResource(R.drawable.bg_analyze)
                contentDescription = "候选招法 ${index + 1}"
                isEnabled = false
                alpha = 0.45f
                setOnClickListener {
                    selectedCandidate = index
                    renderSelectedCandidate()
                }
            }.also { selectors.addView(it, LinearLayout.LayoutParams(dp(36), dp(36))) }
        }
        stopButton = ImageButton(overlayContext).apply {
            setImageResource(R.drawable.ic_stop)
            scaleType = ImageView.ScaleType.CENTER
            setBackgroundResource(R.drawable.bg_analyze)
            setPadding(0, 0, 0, 0)
            contentDescription = "停止屏幕共享和计算"
            setOnClickListener { stopRecognition("已停止屏幕共享") }
        }
        updateStopButton()
        selectors.addView(stopButton, LinearLayout.LayoutParams(dp(36), dp(36)))
        left.addView(selectors)
        scoreView = label("—", 13f).apply {
            setTextColor(Color.rgb(239, 106, 97))
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        left.addView(scoreView)
        root.addView(left, LinearLayout.LayoutParams(dp(184), LinearLayout.LayoutParams.WRAP_CONTENT))
        moveSequence = MoveSequenceView(overlayContext).apply { showStatus(stateMessage) }
        root.addView(moveSequence, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        enableDragging(dragHandle, root, parameters)
        enableDragging(scoreView, root, parameters)
        enableDragging(moveSequence, root, parameters)
        windowManager.addView(root, parameters)
        panel = root; windowParameters = parameters
        windowOpen = true
    }

    private fun enableDragging(title: View, root: View, parameters: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f; var dragging = false
        val slop = ViewConfiguration.get(overlayContext).scaledTouchSlop
        title.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = parameters.x; startY = parameters.y; touchX = event.rawX; touchY = event.rawY; dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX; val dy = event.rawY - touchY
                    dragging = dragging || abs(dx) > slop || abs(dy) > slop
                    if (dragging) {
                        val screen = overlayContext.resources.displayMetrics
                        parameters.x = (startX + dx).roundToInt().coerceIn(EDGE, (screen.widthPixels - root.width - EDGE).coerceAtLeast(EDGE))
                        parameters.y = (startY + dy).roundToInt().coerceIn(EDGE, (screen.heightPixels - root.height - dp(24)).coerceAtLeast(EDGE))
                        windowManager.updateViewLayout(root, parameters)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!dragging) view.performClick(); true }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        clearBoardLocation()
        val root = panel ?: return
        val parameters = windowParameters ?: return
        parameters.width = (overlayContext.resources.displayMetrics.widthPixels - EDGE * 2).coerceAtLeast(1)
        parameters.x = EDGE; parameters.y = EDGE
        windowManager.updateViewLayout(root, parameters)
    }

    override fun onDestroy() {
        windowOpen = false
        destroyed = true
        appOps.stopWatchingMode(permissionListener)
        EngineSettings.preferences(this).unregisterOnSharedPreferenceChangeListener(settingsListener)
        stopRecognition("已关闭", updateForeground = false)
        searches.close()
        arrows.close()
        worker.shutdown()
        handler.removeCallbacksAndMessages(null)
        panel?.let { if (it.isAttachedToWindow) windowManager.removeViewImmediate(it) }
        panel = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun label(value: String, size: Float) = TextView(overlayContext).apply { text = value; textSize = size; setTextColor(Color.WHITE) }
    private fun setAnalysisEnabled(enabled: Boolean) {
        analysisEnabled = enabled
        if (::analysisButton.isInitialized) {
            analysisButton.isSelected = enabled
            analysisButton.contentDescription = if (enabled) "停止屏幕分析" else "开启屏幕分析"
        }
    }
    private fun setObservationEnabled(enabled: Boolean) {
        observationEnabled = enabled
        if (::observationButton.isInitialized) {
            observationButton.isSelected = enabled
            observationButton.contentDescription = if (enabled) "停止观察模式" else "开启观察模式"
        }
    }
    private fun showMoves(moves: List<DisplayMove>?) {
        if (!::moveSequence.isInitialized) return
        moveSequence.showMoves(moves ?: emptyList())
    }
    private fun clearAnalysis() {
        displayedResult = null; selectedCandidate = 0
        arrows.clear()
        showMoves(null)
        if (::scoreView.isInitialized) {
            scoreView.text = "—"
            scoreView.contentDescription = "—，$stateMessage"
        }
        if (::candidateButtons.isInitialized) candidateButtons.forEach {
            it.isSelected = false; it.isEnabled = false; it.alpha = 0.45f
        }
    }
    private fun renderSelectedCandidate() {
        val result = displayedResult ?: return
        showMoves(result.variations[selectedCandidate])
        scoreView.text = result.scores[selectedCandidate]
        scoreView.contentDescription = "${scoreView.text}，$stateMessage"
        candidateButtons.forEachIndexed { index, button ->
            button.isEnabled = index < result.candidateCount
            button.alpha = if (button.isEnabled) 1f else 0.45f
            button.isSelected = index == selectedCandidate && button.isEnabled
        }
        renderHintArrows()
    }
    private fun renderHintArrows() {
        val result = displayedResult
        val geometry = boardGeometry
        if (result != null && geometry != null && recognizedPosition == result.position && hasActiveMode()) {
            arrows.show(geometry, result.hints.getOrNull(selectedCandidate) ?: emptyList())
        } else arrows.clear()
    }
    private fun clearBoardLocation() {
        recognizedPosition = null; boardGeometry = null
        if (::arrows.isInitialized) arrows.clear()
    }
    private fun hideBoardLocation(token: Long) {
        handler.post { if (isCurrent(token)) clearBoardLocation() }
    }
    private fun setStatus(message: String) {
        if (stateMessage != message) {
            stateMessage = message
            if (::scoreView.isInitialized) scoreView.contentDescription = "${scoreView.text}，$message"
        }
        if (displayedResult == null && ::moveSequence.isInitialized) moveSequence.showStatus(message)
    }
    private fun updateTurnButton() {
        // Keep the black-side icon visible.
        turnButton.imageTintList = ColorStateList.valueOf(if (redToMove) Color.rgb(239, 106, 97) else Color.rgb(192, 192, 192))
        turnButton.contentDescription = if (redToMove) "当前红方走，点击切换黑方走" else "当前黑方走，点击切换红方走"
    }
    private fun setThinkingEnabled(enabled: Boolean) {
        thinkingEnabled = enabled
        if (::thinkingButton.isInitialized) {
            thinkingButton.isSelected = enabled
            thinkingButton.contentDescription = if (enabled) "停止后台思考" else "开启后台思考"
        }
    }
    private fun updateStopButton() {
        if (::stopButton.isInitialized) {
            stopButton.isEnabled = capture != null || pendingRequest != -1L
            stopButton.alpha = if (stopButton.isEnabled) 1f else 0.45f
        }
    }
    private fun dp(value: Int) = (value * overlayContext.resources.displayMetrics.density).roundToInt()

    companion object {
        @Volatile var windowOpen = false
            private set
        private const val TAG = "XiangqiAssistant"
        private const val EDGE = 4 // Physical pixels.
        private const val CHANNEL_ID = "xiangqi.floating"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_CLOSE = "xiangqi.floating.CLOSE"
        const val ACTION_CAPTURE_RESULT = "xiangqi.assistant.CAPTURE_RESULT"
        const val EXTRA_RESULT_CODE = "capture.resultCode"
        const val EXTRA_RESULT_DATA = "capture.resultData"
        const val EXTRA_REQUEST_ID = "capture.requestId"
    }
}
