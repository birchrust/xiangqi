package dev.xiangqi.assistant

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle

/** Requests capture without recreating the WebView. */
class CapturePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        try {
            val request = if (Build.VERSION.SDK_INT >= 34) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else manager.createScreenCaptureIntent()
            @Suppress("DEPRECATION")
            startActivityForResult(request, REQUEST_CAPTURE)
        } catch (_: RuntimeException) { report(RESULT_CANCELED, null) }
    }

    @Deprecated("Platform activity result API for the minimal native capture activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE) report(resultCode, data)
    }

    private fun report(resultCode: Int, data: Intent?) {
        val service = Intent(this, FloatingWindowService::class.java)
            .setAction(FloatingWindowService.ACTION_CAPTURE_RESULT)
            .putExtra(FloatingWindowService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(FloatingWindowService.EXTRA_RESULT_DATA, data)
            .putExtra(FloatingWindowService.EXTRA_REQUEST_ID, intent.getLongExtra(FloatingWindowService.EXTRA_REQUEST_ID, -1))
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
        // Return to the previously visible app.
        finishAndRemoveTask()
    }

    companion object { private const val REQUEST_CAPTURE = 900 }
}
