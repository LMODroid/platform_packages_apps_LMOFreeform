package com.libremobileos.freeform.server.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Process
import android.util.Slog
import android.view.Display
import android.view.DisplayInfo
import android.view.IRotationWatcher
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.android.server.LocalServices
import com.android.server.ServiceThread
import com.android.server.wm.WindowManagerInternal
import com.libremobileos.freeform.ILMOFreeformDisplayCallback
import com.libremobileos.freeform.server.util.Debug.dlog
import com.libremobileos.freeform.server.LMOFreeformServiceHolder
import com.libremobileos.freeform.server.SystemServiceHolder
import com.libremobileos.freeform.server.util.dpToPx
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class FreeformWindow(
    private val handler: Handler,
    val context: Context,
    private val appConfig: AppConfig,
    val freeformConfig: FreeformConfig
): TextureView.SurfaceTextureListener, ILMOFreeformDisplayCallback.Stub(), View.OnTouchListener,
    WindowManagerInternal.DisplaySecureContentListener {

    var freeformTaskStackListener: FreeformTaskStackListener? = null
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val windowManagerInt = LocalServices.getService(WindowManagerInternal::class.java)
    val windowParams = WindowManager.LayoutParams()
    private val resourceHolder = RemoteResourceHolder(context, FREEFORM_PACKAGE)
    lateinit var freeformLayout: ViewGroup
    lateinit var freeformRootView: ViewGroup
    lateinit var freeformView: TextureView
    private lateinit var topBarView: View
    private lateinit var bottomBarView: View
    private lateinit var minimizedIconContainer: View
    private lateinit var minimizedIconImage: ImageView
    private var displayId = Display.INVALID_DISPLAY
    var defaultDisplayWidth = context.resources.displayMetrics.widthPixels
    var defaultDisplayHeight = context.resources.displayMetrics.heightPixels
    var defaultDisplayRotation = context.display.rotation
    private val defaultDisplayInfo = DisplayInfo()
    private val destroyRunnable = Runnable { destroy("destroyRunnable", true) }
    /**
     * For calls into the activity task manager and the display manager: those are synchronous and
     * can block for seconds while the window manager lock is held. Running them on [handler] would
     * stop us from reading this window's input channel, which ANRs the window.
     */
    private val workerThread =
        ServiceThread("FreeformWindow", Process.THREAD_PRIORITY_DISPLAY, false /*allowIo*/)
            .apply { start() }
    private val workerHandler = workerThread.threadHandler

    private val rotationWatcher = object : IRotationWatcher.Stub() {
        override fun onRotationChanged(rotation: Int) {
            dlog(TAG, "onRotationChanged($rotation)")
            handler.post {
                defaultDisplayWidth = context.resources.displayMetrics.widthPixels
                defaultDisplayHeight = context.resources.displayMetrics.heightPixels
                defaultDisplayRotation = context.display.rotation
                measureSize()
                changeOrientation()
                if (freeformConfig.isHangUp) toMinimizedIcon()
                else makeSureFreeformInScreen()
                measureScale()
                resizeFreeformDisplay()
            }
        }
    }

    companion object {
        private const val TAG = "LMOFreeform/FreeformWindow"
        private const val FREEFORM_PACKAGE = "com.libremobileos.freeform"
        private const val FREEFORM_LAYOUT = "view_freeform"
        private const val WINDOW_DESTROY_WAIT_MS = 10000L
        private const val SIDEBAR_PACKAGE = "com.libremobileos.sidebar"
        private const val ALL_APP_ACTIVITY = "com.libremobileos.sidebar.ui.all_app.AllAppActivity"
        private const val MINIMIZED_CONTAINER_WIDTH_DP = 88
        private const val MINIMIZED_CONTAINER_HEIGHT_DP = 72
        private const val MINIMIZED_PEEK_OFFSET_DP = 24
    }

    init {
        if (LMOFreeformServiceHolder.ping()) {
            Slog.i(TAG, "FreeformWindow init")
            populateFreeformConfig()
            handler.post { if (!addFreeformView()) destroy("init:addFreeform failed") }
        } else {
            destroy("init:service not running")
            // NOT RUNNING !!!
        }
    }

    override fun onDisplayPaused() {
        //NOT USED
    }

    override fun onDisplayResumed() {
        //NOT USED
    }

    override fun onDisplayStopped() {
        //NOT USED
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        dlog(TAG, "onSurfaceTextureAvailable width:$width height:$height")
        if (displayId < 0) {
            LMOFreeformServiceHolder.createDisplay(freeformConfig, appConfig, Surface(surfaceTexture), this)
        }
        surfaceTexture.setDefaultBufferSize(freeformConfig.freeformWidth, freeformConfig.freeformHeight)
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        surfaceTexture.setDefaultBufferSize(freeformConfig.freeformWidth, freeformConfig.freeformHeight)
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
        //NOT USED
    }

    override fun onDisplayAdd(displayId: Int) {
        Slog.i(TAG, "onDisplayAdd displayId=$displayId, $appConfig")
        handler.post {
            this.displayId = displayId
            freeformTaskStackListener = FreeformTaskStackListener(displayId, this)
            SystemServiceHolder.activityTaskManager.registerTaskStackListener(freeformTaskStackListener)
            if (appConfig.taskId != -1) {
                dlog(TAG, "moving taskId=${appConfig.taskId} to freeform display")
                freeformTaskStackListener!!.taskId = appConfig.taskId
                runCatching {
                    // TODO: find a new way for this since getTaskDescription was removed in fwb commit a7cae90a991e
                    // if (SystemServiceHolder.activityTaskManager.getTaskDescription(appConfig.taskId) == null) {
                    //     throw Exception("stale task")
                    // }
                    SystemServiceHolder.activityTaskManager.moveRootTaskToDisplay(appConfig.taskId, displayId)
                }
                .onFailure { e ->
                    Slog.e(TAG, "failed to move task ${appConfig.taskId}: $e, fallback to startApp")
                    startApp()
                }
            } else if (appConfig.userId == -100) {
                if (appConfig.pendingIntent == null) destroy("onDisplayAdd:userId=-100, but pendingIntent is null")
                else {
                    LMOFreeformServiceHolder.startPendingIntent(appConfig.pendingIntent, displayId)
                }
            } else {
                startApp()
            }

            val rightView = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "rightView")
            if (null == rightView) {
                Slog.e(TAG, "right&rightScale view is null")
                destroy("onDisplayAdd:rightView is null")
                return@post
            }
            rightView.setOnClickListener(RightViewClickListener(displayId))
            rightView.setOnLongClickListener(RightViewLongClickListener(this))
        }
    }

    private fun startApp() {
        if (displayId == Display.INVALID_DISPLAY) {
            Slog.e(TAG, "cannot startApp: displayId not yet set!")
            return
        }
        if (LMOFreeformServiceHolder.startApp(context, appConfig, displayId).not())
            destroy("startApp failed")
    }

    override fun onDisplayHasSecureWindowOnScreenChanged(displayId: Int, hasSecureWindowOnScreen: Boolean) {
        if (displayId != this.displayId) return;
        dlog(TAG, "onDisplayHasSecureWindowOnScreenChanged: $hasSecureWindowOnScreen")
        handler.post {
            windowParams.flags = if (hasSecureWindowOnScreen) {
                windowParams.flags or WindowManager.LayoutParams.FLAG_SECURE
            } else {
                windowParams.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
            }
            runCatching { windowManager.updateViewLayout(freeformLayout, windowParams) }
                .onFailure { Slog.e(TAG, "updateViewLayout failed: $it") }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val pointerCoords: Array<MotionEvent.PointerCoords?> = arrayOfNulls(event.pointerCount)
        val pointerProperties: Array<MotionEvent.PointerProperties?> = arrayOfNulls(event.pointerCount)
        for (i in 0 until event.pointerCount) {
            val oldCoords = MotionEvent.PointerCoords()
            val pointerProperty = MotionEvent.PointerProperties()
            event.getPointerCoords(i, oldCoords)
            event.getPointerProperties(i, pointerProperty)
            pointerCoords[i] = oldCoords
            pointerCoords[i]!!.apply {
                x = oldCoords.x * freeformConfig.scale
                y = oldCoords.y * freeformConfig.scale
            }
            pointerProperties[i] = pointerProperty
        }

        val newEvent = MotionEvent.obtain(
            event.downTime,
            event.eventTime,
            event.action,
            event.pointerCount,
            pointerProperties,
            pointerCoords,
            event.metaState,
            event.buttonState,
            event.xPrecision,
            event.yPrecision,
            event.deviceId,
            event.edgeFlags,
            event.source,
            event.flags
        )
        LMOFreeformServiceHolder.touch(newEvent, displayId)
        newEvent.recycle()
        return true
    }

    /**
     * get freeform screen dimen / freeform view dimen
     */
    private fun populateFreeformConfig() {
        measureSize()
        measureScale()
        context.display.getDisplayInfo(defaultDisplayInfo)
        freeformConfig.apply {
            refreshRate = defaultDisplayInfo.refreshRate
            presentationDeadlineNanos = defaultDisplayInfo.presentationDeadlineNanos
            dlog(TAG, "populateFreeformConfig: $this")
        }
    }

    fun measureSize() {
        val isPortrait = defaultDisplayRotation == Surface.ROTATION_0 ||
                defaultDisplayRotation == Surface.ROTATION_180
        freeformConfig.apply {
            height = (defaultDisplayHeight * (if (isPortrait) 0.4 else 0.7)).roundToInt()
            width = if (isPortrait) {
                (defaultDisplayWidth * 0.7).roundToInt()
            } else {
                // preserving the aspect ratio
                defaultDisplayHeight * defaultDisplayHeight / defaultDisplayWidth
            }
            dlog(TAG, "measureSize: isPortrait=$isPortrait width=$width height=$height")
        }
    }

    fun measureScale() {
        freeformConfig.apply {
            val widthScale = min(defaultDisplayWidth, defaultDisplayHeight) * 1.0f / min(width, height)
            val heightScale = max(defaultDisplayWidth, defaultDisplayHeight) * 1.0f / max(width, height)
            scale = min(widthScale, heightScale)
            freeformWidth = (width * scale).roundToInt()
            freeformHeight = (height * scale).roundToInt()
            dlog(TAG, "measureScale: $scale freeformWidth=$freeformWidth freeformHeight=$freeformHeight")
        }
    }

    /**
     * Called in system handler
     */
    @SuppressLint("WrongConstant")
    private fun addFreeformView(): Boolean {
        dlog(TAG, "addFreeformView")
        val tmpFreeformLayout = resourceHolder.getLayout(FREEFORM_LAYOUT)!! ?: return false
        freeformLayout = tmpFreeformLayout
        freeformRootView = resourceHolder.getLayoutChildViewByTag<FrameLayout>(freeformLayout, "freeform_root") ?: return false
        topBarView = resourceHolder.getLayoutChildViewByTag(freeformLayout, "topBarView") ?: return false
        bottomBarView = resourceHolder.getLayoutChildViewByTag(freeformLayout, "bottomBarView") ?: return false
        minimizedIconContainer = resourceHolder.getLayoutChildViewByTag(freeformLayout, "minimizedIconContainer") ?: return false
        minimizedIconImage = resourceHolder.getLayoutChildViewByTag(freeformLayout, "minimizedIconImage") ?: return false
        val middleView = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "middleView") ?: return false
        val moveTouchListener = MoveTouchListener(this)
        topBarView.setOnTouchListener(moveTouchListener)
        middleView.setOnTouchListener(moveTouchListener)
        val leftView = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "leftView") ?: return false
        val leftScaleView = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "leftScaleView") ?: return false
        val rightScaleView = resourceHolder.getLayoutChildViewByTag<View>(freeformLayout, "rightScaleView") ?: return false
        leftView.setOnClickListener(LeftViewClickListener(this))
        if (!(appConfig.packageName == SIDEBAR_PACKAGE && appConfig.activityName == ALL_APP_ACTIVITY)) {
            // Sidebar all apps activity should not be fullscreen.
            leftView.setOnLongClickListener(LeftViewLongClickListener(this))
        }
        leftScaleView.setOnTouchListener(ScaleTouchListener(this, false))
        rightScaleView.setOnTouchListener(ScaleTouchListener(this))

        freeformView = FreeformTextureView(context).apply {
            setOnTouchListener(this@FreeformWindow)
            surfaceTextureListener = this@FreeformWindow
        }
        freeformRootView.layoutParams = freeformRootView.layoutParams.apply {
            width = freeformConfig.width
            height = freeformConfig.height
        }
        freeformRootView.addView(freeformView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        windowParams.apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            format = PixelFormat.RGBA_8888
            windowAnimations = android.R.style.Animation_Dialog
        }
        runCatching {
            windowManager.addView(freeformLayout, windowParams)
            SystemServiceHolder.windowManager.watchRotation(rotationWatcher, Display.DEFAULT_DISPLAY)
            windowManagerInt.registerDisplaySecureContentListener(this)
        }.onFailure {
            Slog.e(TAG, "addView failed: $it")
            return false
        }
        return true
    }

    /**
     * Called in system handler
     */
    @SuppressLint("ClickableViewAccessibility")
    fun handleHangUp() {
        dlog(TAG, "handleHangUp isHangUp=${freeformConfig.isHangUp}")
        if (freeformConfig.isHangUp) {
            freeformConfig.apply {
                inHangUpX = windowParams.x
                inHangUpY = windowParams.y
            }
            windowParams.apply {
                x = freeformConfig.notInHangUpX
                y = freeformConfig.notInHangUpY
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
                flags = flags or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            }
            freeformRootView.layoutParams = freeformRootView.layoutParams.apply {
                width = freeformConfig.width
                height = freeformConfig.height
            }
            windowManager.updateViewLayout(freeformLayout, windowParams)
            minimizedIconContainer.setOnTouchListener(null)
            minimizedIconContainer.visibility = View.GONE
            freeformRootView.visibility = View.VISIBLE
            topBarView.visibility = View.VISIBLE
            bottomBarView.visibility = View.VISIBLE
            freeformConfig.isHangUp = false
            freeformView.setOnTouchListener(this)
        } else {
            freeformConfig.apply {
                notInHangUpX = windowParams.x
                notInHangUpY = windowParams.y
            }
            topBarView.visibility = View.GONE
            bottomBarView.visibility = View.GONE
            freeformRootView.visibility = View.GONE
            minimizedIconContainer.visibility = View.VISIBLE
            minimizedIconContainer.setOnTouchListener(MinimizedIconTouchListener(this))
            windowParams.flags = windowParams.flags xor WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            freeformConfig.isHangUp = true
            toMinimizedIcon()
        }
    }

    /**
     * Called in system handler
     */
    fun toMinimizedIcon() {
        val appIcon =
            runCatching { context.packageManager.getApplicationIcon(appConfig.packageName) }
            .getOrElse { err ->
                Slog.e(TAG, "failed to load app icon: $err")
                context.packageManager.defaultActivityIcon
            }
        val containerWidth = MINIMIZED_CONTAINER_WIDTH_DP.dpToPx(context).roundToInt()
        val containerHeight = MINIMIZED_CONTAINER_HEIGHT_DP.dpToPx(context).roundToInt()
        val peekOffset = MINIMIZED_PEEK_OFFSET_DP.dpToPx(context).roundToInt()
        minimizedIconImage.setImageDrawable(appIcon)
        windowParams.apply {
            width = containerWidth
            height = containerHeight
            x = if (freeformConfig.inHangUpX != -1) freeformConfig.inHangUpX
                else defaultDisplayWidth / 2 - containerWidth / 2 + peekOffset
            y = if (freeformConfig.inHangUpY != -1) freeformConfig.inHangUpY
                else (-defaultDisplayHeight / 2 * 0.7).roundToInt()
        }
        runCatching { windowManager.updateViewLayout(freeformLayout, windowParams) }
            .onFailure { Slog.e(TAG, "$it") }
    }

    /**
     * Called in uiHandler
     */
    fun makeSureFreeformInScreen() {
        if (freeformConfig.isHangUp) return
        val maxWidth = defaultDisplayWidth
        val maxHeight = (defaultDisplayHeight * 0.9).roundToInt()
        if (freeformRootView.layoutParams.width > maxWidth || freeformRootView.layoutParams.height > maxHeight) {
            freeformRootView.layoutParams = freeformRootView.layoutParams.apply {
                width = min(freeformRootView.width, maxWidth)
                height = min(freeformRootView.height, maxHeight)
            }
        }
        if (windowParams.x < -(defaultDisplayWidth / 2)) FreeformAnimation.moveInScreenAnimator(windowParams.x, -(defaultDisplayWidth / 2), 300, true, this)
        else if (windowParams.x > (defaultDisplayWidth / 2)) FreeformAnimation.moveInScreenAnimator(windowParams.x, (defaultDisplayWidth / 2), 300, true, this)
        if (windowParams.y < -(defaultDisplayHeight / 2)) FreeformAnimation.moveInScreenAnimator(windowParams.y, -(defaultDisplayHeight / 2), 300, false, this)
        else if (windowParams.y > (defaultDisplayHeight / 2)) FreeformAnimation.moveInScreenAnimator(windowParams.y, (defaultDisplayHeight / 2), 300, false, this)
    }

    fun resizeFreeformDisplay() {
        workerHandler.post {
            LMOFreeformServiceHolder.resizeFreeform(
                this@FreeformWindow,
                freeformConfig.freeformWidth,
                freeformConfig.freeformHeight,
                freeformConfig.densityDpi
            )
            freeformView.surfaceTexture?.setDefaultBufferSize(
                freeformConfig.freeformWidth,
                freeformConfig.freeformHeight
            )
        }
    }

    /**
     * Change freeform orientation
     * Called in system handler
     */
    fun changeOrientation() {
        if (freeformConfig.isHangUp) return
        freeformRootView.layoutParams = freeformRootView.layoutParams.apply {
            width = freeformConfig.width
            height = freeformConfig.height
        }
    }

    fun getFreeformId(): String {
        return "${appConfig.packageName},${appConfig.activityName},${appConfig.userId}"
    }

    /**
     * Runs [action] on the thread that owns this window's views.
     */
    fun postOnHandler(action: FreeformWindow.() -> Unit) {
        handler.post { action() }
    }

    /**
     * Runs [action] off the window handler, for calls that can block. See [workerThread].
     */
    fun postOnWorkerHandler(action: FreeformWindow.() -> Unit) {
        workerHandler.post { action() }
    }

    fun close() {
        dlog(TAG, "close()")
        workerHandler.post {
            runCatching {
                SystemServiceHolder.activityTaskManager.removeTask(freeformTaskStackListener!!.taskId)
                removeView()
            }.onFailure { exception ->
                Slog.e(TAG, "removeTask failed: ", exception)
                destroy("window.close() fallback")
            }
        }
    }

    fun removeView(runDestroy: Boolean = true) {
        dlog(TAG, "removeView($runDestroy)")
        handler.removeCallbacks(destroyRunnable)
        handler.post {
            runCatching {
                windowManager.removeViewImmediate(freeformLayout)
                dlog(TAG, "removeView success")
            }.onFailure { exception ->
                Slog.e(TAG, "removeView failed $exception")
            }
        }
        // wait for onTaskRemoved(), but take it into our own hands in case its never triggered.
        if (runDestroy)
            handler.postDelayed(destroyRunnable, WINDOW_DESTROY_WAIT_MS)
    }

    fun destroy(callReason: String, shouldRemoveTask: Boolean = false) {
        Slog.i(TAG, "destroy ${getFreeformId()}, displayId=$displayId callReason: $callReason")
        removeView(false)
        handler.removeCallbacks(destroyRunnable)
        SystemServiceHolder.activityTaskManager.unregisterTaskStackListener(freeformTaskStackListener)
        SystemServiceHolder.windowManager.removeRotationWatcher(rotationWatcher)
        LMOFreeformServiceHolder.releaseFreeform(this)
        FreeformWindowManager.removeWindow(getFreeformId())
        windowManagerInt.unregisterDisplaySecureContentListener(this)
        freeformTaskStackListener!!.taskId.let {
            if (it != -1 && shouldRemoveTask) {
                Slog.i(TAG, "destroy: remove taskId $it again")
                runCatching { SystemServiceHolder.activityTaskManager.removeTask(it) }
            }
        }
    }
}
