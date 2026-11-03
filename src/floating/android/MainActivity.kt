package dev.dioxus.main

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.Keep
import dev.xiangqi.assistant.EngineSettings
import dev.xiangqi.assistant.FloatingWindowService

typealias BuildConfig = com.example.xiangqi.BuildConfig

class MainActivity : WryActivity() {
    private var waitingForOverlayPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        waitingForOverlayPermission = savedInstanceState?.getBoolean(PENDING_PERMISSION) ?: false
    }

    // JNI entry point; preserve the release method name.
    @Keep
    fun showFloatingWindow() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (Settings.canDrawOverlays(this)) {
                startFloatingService()
            } else {
                waitingForOverlayPermission = true
                val label = applicationInfo.loadLabel(packageManager)
                Toast.makeText(this, "请选择“$label”，开启悬浮窗权限后返回。", Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } catch (error: ActivityNotFoundException) {
                    waitingForOverlayPermission = false
                    Toast.makeText(this, "无法打开悬浮窗权限设置，请在系统设置中手动开启。", Toast.LENGTH_LONG).show()
                    Log.e(TAG, "Overlay permission settings unavailable", error)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForOverlayPermission) {
            waitingForOverlayPermission = false
            if (Settings.canDrawOverlays(this)) {
                startFloatingService()
            } else {
                Toast.makeText(this, "未开启悬浮窗权限，可再次点击“启动”授权。", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Keep
    fun closeFloatingWindow() {
        runOnUiThread {
            waitingForOverlayPermission = false
            stopService(Intent(this, FloatingWindowService::class.java))
        }
    }

    @Keep
    fun isFloatingWindowOpen(): Boolean = FloatingWindowService.windowOpen

    @Keep
    fun showEngineSettings() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val padding = (24 * resources.displayMetrics.density).toInt()
            val depth = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
                setText(EngineSettings.maxDepth(this@MainActivity).toString())
                contentDescription = "最大搜索深度 depth"
                selectAll()
            }
            val form = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, 0)
                addView(TextView(this@MainActivity).apply { text = "最大搜索深度（depth）" })
                addView(depth)
                addView(TextView(this@MainActivity).apply {
                    text = "范围 ${EngineSettings.DEPTH_RANGE}，默认 ${EngineSettings.DEFAULT_DEPTH}。持续搜索到深度上限；确认走子后自动接续新局面。"
                })
            }
            val dialog = AlertDialog.Builder(this).setTitle("设置").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = depth.text.toString().toIntOrNull()
                    if (value == null || value !in 1..128) {
                        depth.error = "请输入 ${EngineSettings.DEPTH_RANGE} 的整数"
                    } else {
                        EngineSettings.setMaxDepth(this, value)
                        dialog.dismiss()
                    }
                }
            }
            dialog.show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(PENDING_PERMISSION, waitingForOverlayPermission)
        super.onSaveInstanceState(outState)
    }

    private fun startFloatingService() {
        val intent = Intent(this, FloatingWindowService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (error: RuntimeException) {
            Toast.makeText(this, "悬浮窗启动失败，请返回应用后重试。", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Unable to start floating service", error)
        }
    }

    companion object {
        private const val PENDING_PERMISSION = "floating.pendingPermission"
        private const val TAG = "XiangqiFloating"
    }
}
