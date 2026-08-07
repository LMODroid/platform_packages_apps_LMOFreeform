package com.libremobileos.sidebar.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.libremobileos.sidebar.app.SidebarApplication
import com.libremobileos.sidebar.bean.AppInfo
import com.libremobileos.sidebar.ui.theme.SidebarTheme
import com.libremobileos.sidebar.utils.Logger
import kotlin.math.roundToInt

/**
 * @author KindBrave
 * @since 2023/9/26
 */
class SidebarView(
    private val context: Context,
    private val viewModel: ServiceViewModel,
    private val callback: Callback
) : SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle get() = lifecycleRegistry

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val composeView: View
    private var sidebarPositionX = 0
    private var sidebarPositionY = 0
    private var isShowing = false
    private var isRemoved = false
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val layoutParams = LayoutParams()
    private val logger = Logger(TAG)
    private val handler = Handler(Looper.getMainLooper())

    private val sharedPrefs by lazy {
        context.getSharedPreferences(SidebarApplication.CONFIG, Context.MODE_PRIVATE)
    }

    companion object {
        private const val OFFSET_X = 90
        private const val PACKAGE = "com.libremobileos.freeform"
        private const val ACTION = "com.libremobileos.freeform.START_FREEFORM"
        private const val TAG = "SidebarView"
    }

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@SidebarView)
            setViewTreeSavedStateRegistryOwner(this@SidebarView)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SidebarTheme {
                    SidebarComposeView(
                        viewModel = viewModel,
                        launchApp = { launchAppInFreeform(it) },
                        closeSidebar = { removeView() },
                        modifier = Modifier
                            .fillMaxHeight()
                            .wrapContentWidth()
                    )
                }
            }
        }
    }

    private fun launchAppInFreeform(appInfo: AppInfo) {
        val intent = Intent(ACTION).apply {
            setPackage(PACKAGE)
            putExtra("packageName", appInfo.packageName)
            putExtra("activityName", appInfo.activityName)
            putExtra("userId", appInfo.userId)
        }
        context.sendBroadcast(intent)
        removeView()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showView() {
        if (isShowing || isRemoved) return

        layoutParams.apply {
            type = LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    LayoutParams.FLAG_HARDWARE_ACCELERATED
            privateFlags = LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY or
                    LayoutParams.PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY
            format = PixelFormat.RGBA_8888
            windowAnimations = android.R.style.Animation_Dialog
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }

        updateSidebarPosition()
        isShowing = true

        composeView.translationX = sidebarPositionX * 1.0f * 200
        composeView.setOnTouchListener { view, event ->
            logger.d("composeView: $event")
            if (event.action == MotionEvent.ACTION_UP) {
                removeView()
                true
            }
            false
        }

        handler.post {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) // this also emits ON_START
            runCatching {
                windowManager.addView(composeView, layoutParams)
                composeView.animate().translationX(0f).setDuration(300).start()
            }.onFailure {
                logger.e("wm.addView failed, bail out", it)
                removeView()
            }
        }
    }

    fun removeView() {
        if (isRemoved) return

        logger.d("removeView")
        isRemoved = true
        isShowing = false
        handler.post {
            runCatching {
                windowManager.removeViewImmediate(composeView)
            }.onFailure {
                logger.e("wm.removeViewImmediate failed", it)
            }
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            callback.onRemove()
        }
    }

    fun updateSidebarPosition() {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        val sidebarHeight = if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            screenHeight / 3
        } else {
            (screenHeight * 0.8f).roundToInt()
        }

        sidebarPositionX = sharedPrefs.getInt(SidebarService.SIDELINE_POSITION_X, 1)
        sidebarPositionY = if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            sharedPrefs.getInt(SidebarService.SIDELINE_POSITION_Y_PORTRAIT, -screenHeight / 6)
        } else {
            0
        }

        layoutParams.apply {
            width = LayoutParams.WRAP_CONTENT
            height = sidebarHeight
            x = sidebarPositionX * (screenWidth / 2 - OFFSET_X)
            y = sidebarPositionY
        }

        logger.d("updateSidebarPosition: posX=$sidebarPositionX posY=$sidebarPositionY" +
                " lp.x=${layoutParams.x} lp.y=${layoutParams.y} height=${layoutParams.height}")

        if (isShowing) {
            handler.post {
                runCatching {
                    windowManager.updateViewLayout(composeView, layoutParams)
                }.onFailure { e ->
                    logger.e("failed to updateViewLayout: ${e.message}")
                }
            }
        }
    }

    interface Callback {
        fun onRemove()
    }
}
