package com.libremobileos.freeform.server.ui

import android.annotation.SuppressLint
import android.util.Slog
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.libremobileos.freeform.server.LMOFreeformServiceHolder
import com.libremobileos.freeform.server.SystemServiceHolder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class MoveTouchListener(
    private val window: FreeformWindow
) : View.OnTouchListener{
    private var startX = 0.0f
    private var startY = 0.0f
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                window.windowManager.updateViewLayout(window.freeformLayout, window.windowParams.apply {
                    x = (x + event.rawX - startX).roundToInt()
                    y = (y + event.rawY - startY).roundToInt()
                })
                startX = event.rawX
                startY = event.rawY
            }
            MotionEvent.ACTION_UP -> {
                window.makeSureFreeformInScreen()
            }
        }
        return true
    }
}

class LeftViewClickListener(private val window: FreeformWindow) : View.OnClickListener {
    override fun onClick(v: View) {
        window.close()
    }

}

/**
 * to full screen
 */
class LeftViewLongClickListener(private val window: FreeformWindow): View.OnLongClickListener {
    companion object {
        private const val TAG = "LMOFreeform/TouchListener"
    }
    override fun onLongClick(v: View): Boolean {
        if (null != window.freeformTaskStackListener) {
            val taskId = window.freeformTaskStackListener!!.taskId
            if (taskId == -1) {
                Slog.e(TAG, "taskId is -1, cannot move")
                return true
            }
            window.postOnWorkerHandler {
                runCatching { SystemServiceHolder.activityTaskManager.moveRootTaskToDisplay(taskId, Display.DEFAULT_DISPLAY) }
                    .onFailure { Slog.e(TAG, "moveRootTaskToDisplay failed: $it") }
            }
        }
        // not required because taskStackListener's onTaskDisplayChanged() will be called
        // which in turn calls destroy()
        // window.destroy("MaximizeButtonClickListener")
        return true
    }
}

/**
 * change orientation
 */
class RightViewLongClickListener(private val window: FreeformWindow): View.OnLongClickListener {
    override fun onLongClick(v: View): Boolean {
        window.postOnHandler {
            // hangup
            handleHangUp()
        }
        return true
    }
}

class RightViewClickListener(private val displayId: Int) : View.OnClickListener {
    override fun onClick(v: View) {
        LMOFreeformServiceHolder.back(displayId)
    }
}

class ScaleTouchListener(private val window: FreeformWindow, private val isRight: Boolean = true): View.OnTouchListener {
    private var startX = 0.0f
    private var startY = 0.0f
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                window.freeformRootView.layoutParams = window.freeformRootView.layoutParams.apply {
                    val xDelta = if (isRight) (event.rawX - startX) else (startX - event.rawX)
                    val yDelta = event.rawY - startY
                    width = max(25, (window.freeformRootView.width + xDelta).roundToInt())
                    height = max(25, (window.freeformRootView.height + yDelta).roundToInt())
                    if (width > height) {
                        if (xDelta < 0) width = height
                        else height = width
                    }
                }
                startX = event.rawX
                startY = event.rawY
            }
            MotionEvent.ACTION_UP -> {
                if (window.freeformView.surfaceTexture != null) {
                    window.freeformConfig.width = window.freeformRootView.layoutParams.width
                    window.freeformConfig.height = window.freeformRootView.layoutParams.height
                    window.postOnHandler { makeSureFreeformInScreen() }
                    window.measureScale()
                    window.resizeFreeformDisplay()
                }
            }
        }
        return true
    }
}

class MinimizedIconTouchListener(private val window: FreeformWindow) : View.OnTouchListener {
    private var startRawY = 0f
    private var startWindowY = 0
    private var hasMoved = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startRawY = event.rawY
                startWindowY = window.windowParams.y
                hasMoved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - startRawY
                if (abs(dy) > ViewConfiguration.get(v.context).scaledTouchSlop) {
                    hasMoved = true
                }
                val iconSizePx = window.windowParams.height
                val maxY = window.defaultDisplayHeight / 2 - iconSizePx / 2
                val minY = -(window.defaultDisplayHeight / 2 - iconSizePx / 2)
                window.windowManager.updateViewLayout(window.freeformLayout, window.windowParams.apply {
                    y = (startWindowY + dy).roundToInt().coerceIn(minY, maxY)
                })
            }
            MotionEvent.ACTION_UP -> {
                if (!hasMoved) {
                    window.postOnHandler { handleHangUp() }
                }
            }
        }
        return true
    }
}
