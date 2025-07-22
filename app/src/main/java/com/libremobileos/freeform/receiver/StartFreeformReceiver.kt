package com.libremobileos.freeform.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.libremobileos.freeform.LMOFreeform
import com.libremobileos.freeform.LMOFreeformServiceManager
import com.libremobileos.freeform.utils.Debug
import com.libremobileos.freeform.utils.Logger
import kotlin.math.roundToInt

/**
 * @author KindBrave
 * @since 2023/9/19
 */
class StartFreeformReceiver : BroadcastReceiver() {
    private val logger = Logger(TAG)
    companion object {
        private const val TAG = "StartFreeformReceiver"
        private const val ACTION = "com.libremobileos.freeform.START_FREEFORM"
    }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION) {
            if (Debug.isDebug) logger.d("onReceive ${intent.extras}")
            val packageName = intent.getStringExtra("packageName")
            val activityName = intent.getStringExtra("activityName")
            val userId = intent.getIntExtra("userId", 0)
            val taskId = intent.getIntExtra("taskId", -1)
            val sp = context.getSharedPreferences(LMOFreeform.CONFIG, Context.MODE_PRIVATE)
            val screenWidth = context.resources.displayMetrics.widthPixels
            val screenHeight = context.resources.displayMetrics.heightPixels
            val screenDensityDpi = context.resources.displayMetrics.densityDpi
            val freeformWidth = intent.getIntExtra("width", sp.getInt("freeform_width", (screenWidth * 0.7f).roundToInt()))
            val freeformHeight = intent.getIntExtra("height", sp.getInt("freeform_height", (screenHeight * 0.4f).roundToInt()))
            val dpi = intent.getIntExtra("dpi", sp.getInt("freeform_dpi", screenDensityDpi))
            val offsetX = intent.getIntExtra("offsetX", ((screenWidth - freeformWidth) / 2).roundToInt())
            val offsetY = intent.getIntExtra("offsetY", ((screenHeight - freeformHeight) / 2).roundToInt())

            if (packageName != null && activityName != null) {
                LMOFreeformServiceManager.createWindow(
                    packageName,
                    activityName,
                    userId,
                    taskId,
                    freeformWidth,
                    freeformHeight,
                    dpi,
                    offsetX,
                    offsetY
                )
            }
        }
    }
}
