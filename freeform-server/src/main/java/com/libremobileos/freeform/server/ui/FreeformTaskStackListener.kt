package com.libremobileos.freeform.server.ui

import android.app.ActivityManager
import android.app.ITaskStackListener
import android.content.ComponentName
import android.view.Display
import android.window.TaskSnapshot
import com.libremobileos.freeform.server.util.Debug.dlog
import kotlin.math.max
import kotlin.math.min

class FreeformTaskStackListener(
    private val displayId: Int,
    private val window: FreeformWindow
) : ITaskStackListener.Stub() {

    var taskId = -1

    companion object {
        private const val TAG = "LMOFreeform/FreeformTaskStackListener"

        const val PORTRAIT = 1
        const val LANDSCAPE_1 = 0
        const val LANDSCAPE_2 = 6
    }

    override fun onTaskStackChanged() {

    }

    override fun onActivityPinned(packageName: String, userId: Int, taskId: Int, stackId: Int) {

    }

    override fun onActivityUnpinned() {

    }

    override fun onActivityRestartAttempt(
        task: ActivityManager.RunningTaskInfo?,
        homeTaskVisible: Boolean,
        clearedTask: Boolean,
        wasVisible: Boolean
    ) {

    }

    override fun onActivityForcedResizable(packageName: String, taskId: Int, reason: Int) {

    }

    override fun onActivityDismissingDockedTask() {

    }

    override fun onActivityLaunchOnSecondaryDisplayFailed(
        taskInfo: ActivityManager.RunningTaskInfo?,
        requestedDisplayId: Int
    ) {

    }

    override fun onActivityLaunchOnSecondaryDisplayRerouted(
        taskInfo: ActivityManager.RunningTaskInfo?,
        requestedDisplayId: Int
    ) {

    }

    override fun onTaskCreated(taskId: Int, componentName: ComponentName?) {

    }

    override fun onTaskRemoved(taskId: Int) {
        if (this.taskId == taskId) {
            dlog(TAG, "onTaskRemoved $taskId")
            window.postOnHandler { destroy("onTaskRemoved", true) }
        }
    }

    override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo?) {
        val displayId = taskInfo?.displayId ?: return
        if (this.displayId == displayId) {
            // TODO: move to android.provider.Settings
            // if (FreeformWindowManager.settings.showImeInFreeform) {
            //     SystemServiceHolder.windowManager.setDisplayImePolicy(displayId, 0)
            // }
            taskId = taskInfo.taskId
            dlog(TAG, "onTaskMovedToFront $taskId")
        }
    }

    override fun onTaskDescriptionChanged(taskInfo: ActivityManager.RunningTaskInfo?) {
        val displayId = taskInfo?.displayId ?: return
        if (this.displayId == displayId) {
            taskId = taskInfo.taskId
            dlog(TAG, "onTaskDescriptionChanged $taskInfo")
        }
    }

    override fun onActivityRequestedOrientationChanged(taskId: Int, requestedOrientation: Int) {

    }

    override fun onTaskRemovalStarted(taskInfo: ActivityManager.RunningTaskInfo?) {
        val displayId = taskInfo?.displayId ?: return
        if (this.displayId == displayId) {
            taskId = taskInfo.taskId
            dlog(TAG, "onTaskRemovalStarted $taskId")
            // window.removeView()
        }
    }

    override fun onTaskProfileLocked(taskInfo: ActivityManager.RunningTaskInfo, userId: Int) {

    }

    override fun onTaskSnapshotChanged(taskId: Int, snapshot: TaskSnapshot) {

    }

    override fun onBackPressedOnTaskRoot(taskInfo: ActivityManager.RunningTaskInfo?) {

    }

    override fun onTaskDisplayChanged(taskId: Int, newDisplayId: Int) {
        if (taskId == this.taskId && newDisplayId == Display.DEFAULT_DISPLAY) {
            window.postOnHandler { destroy("onTaskDisplayChanged: $taskId to main display") }
        } else if (newDisplayId == displayId) {
            this.taskId = taskId
            dlog(TAG, "onTaskDisplayChanged: $taskId to freeform display")
        }
    }

    override fun onRecentTaskListUpdated() {

    }

    override fun onRecentTaskListFrozenChanged(frozen: Boolean) {

    }

    override fun onRecentTaskRemovedForAddTask(taskId: Int) {

    }

    override fun onTaskFocusChanged(taskId: Int, focused: Boolean) {

    }

    override fun onTaskRequestedOrientationChanged(taskId: Int, requestedOrientation: Int) {
        if (taskId == this.taskId) {
            dlog(TAG, "onTaskRequestedOrientationChanged: $requestedOrientation")
            window.postOnHandler {
                val max = max(freeformConfig.width, freeformConfig.height)
                val min = min(freeformConfig.width, freeformConfig.height)
                val maxHangUp = max(freeformConfig.hangUpWidth, freeformConfig.hangUpHeight)
                val minHangUp = min(freeformConfig.hangUpWidth, freeformConfig.hangUpHeight)
                when (requestedOrientation) {
                    PORTRAIT -> {
                        dlog(TAG, "PORTRAIT")
                        freeformConfig.width = min
                        freeformConfig.height = max
                        freeformConfig.hangUpWidth = minHangUp
                        freeformConfig.hangUpHeight = maxHangUp
                    }
                    LANDSCAPE_1, LANDSCAPE_2 -> {
                        dlog(TAG, "LANDSCAPE")
                        freeformConfig.width = max
                        freeformConfig.height = min
                        freeformConfig.hangUpWidth = maxHangUp
                        freeformConfig.hangUpHeight = minHangUp
                    }
                }
                changeOrientation()
            }
        }
    }

    override fun onActivityRotation(displayId: Int) {

    }

    override fun onTaskMovedToBack(taskInfo: ActivityManager.RunningTaskInfo?) {

    }

    override fun onLockTaskModeChanged(mode: Int) {

    }

    override fun onTaskSnapshotInvalidated(taskId: Int) {

    }
}
