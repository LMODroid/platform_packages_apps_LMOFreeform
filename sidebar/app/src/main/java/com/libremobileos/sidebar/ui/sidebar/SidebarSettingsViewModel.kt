package com.libremobileos.sidebar.ui.sidebar

import android.annotation.SuppressLint
import android.app.Application
import android.app.prediction.AppPredictionContext
import android.app.prediction.AppPredictionManager
import android.app.prediction.AppPredictor
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_PROFILE_AVAILABLE
import android.content.Intent.ACTION_PROFILE_UNAVAILABLE
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.HandlerExecutor
import android.os.UserHandle
import android.os.UserManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.libremobileos.sidebar.app.SidebarApplication
import com.libremobileos.sidebar.bean.SidebarAppInfo
import com.libremobileos.sidebar.room.DatabaseRepository
import com.libremobileos.sidebar.service.ServiceViewModel.Companion.KEY_SHOW_PREDICTED_APPS
import com.libremobileos.sidebar.service.ServiceViewModel.Companion.MAX_PREDICTED_APPS
import com.libremobileos.sidebar.service.ServiceViewModel.Companion.PREDICTION_UI_SURFACE
import com.libremobileos.sidebar.service.SidebarService
import com.libremobileos.sidebar.utils.Logger
import com.libremobileos.sidebar.utils.appKey
import com.libremobileos.sidebar.utils.contains
import com.libremobileos.sidebar.utils.getSidebarFilteredUsers
import com.libremobileos.sidebar.utils.isResizeableActivity
import com.libremobileos.sidebar.utils.toAppKey
import com.libremobileos.sidebar.utils.toKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.core.content.edit

/**
 * @author KindBrave
 * @since 2023/10/21
 */
@SuppressLint("MissingPermission")
class SidebarSettingsViewModel(private val application: Application) : AndroidViewModel(application) {
    private val logger = Logger("SidebarSettingsViewModel")
    private val repository = DatabaseRepository(application)
    private val allAppList = ArrayList<SidebarAppInfo>()
    private val appComparator = AppComparator()

    private val _allApps = MutableStateFlow<List<SidebarAppInfo>>(emptyList())
    private val pinnedKeys = MutableStateFlow<Set<String>>(emptySet())
    private val predictedKeys = MutableStateFlow<Set<String>>(emptySet())

    val appListFlow: StateFlow<List<SidebarAppInfo>> =
        combine(_allApps, pinnedKeys, predictedKeys) { apps, pinned, predicted ->
            apps.map { app ->
                val key = app.toKey()
                app.copy(
                    isPinned = key in pinned,
                    // the sidebar drops suggestions that are already pinned
                    isPredicted = key in predicted && key !in pinned
                )
            }
        }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isEnabled = UserHandle.myUserId() == 0
    private val appContext = application.applicationContext
    private lateinit var launcherApps: LauncherApps
    private lateinit var userManager: UserManager
    private var appPredictionManager: AppPredictionManager? = null
    private lateinit var sp: SharedPreferences

    private val handlerExecutor = HandlerExecutor(Handler())
    private var appPredictor: AppPredictor? = null
    private val mutex = Mutex()

    private val userProfileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            logger.d("userProfileReceiver received ${intent.action}")
            initAllAppList()
        }
    }

    private val appPredictionCallback = AppPredictor.Callback { targets ->
        logger.d("appPredictionCallback targets: ${targets.size}")
        predictedKeys.value = targets
            .take(MAX_PREDICTED_APPS)
            .mapNotNull { it.toAppKey() }
            .toSet()
    }

    init {
        if (isEnabled) {
            logger.d("init")
            launcherApps = application.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            userManager = application.getSystemService(Context.USER_SERVICE) as UserManager
            appPredictionManager = application.getSystemService(AppPredictionManager::class.java)
            sp = appContext.getSharedPreferences(SidebarApplication.CONFIG, Context.MODE_PRIVATE)

            if (isPredictedAppsEnabled()) registerAppPredictionCallback()
            observePinnedApps()
            initAllAppList()
            appContext.registerReceiverAsUser(
                userProfileReceiver,
                UserHandle.CURRENT,
                IntentFilter().apply {
                    addAction(ACTION_PROFILE_AVAILABLE)
                    addAction(ACTION_PROFILE_UNAVAILABLE)
                },
                null,
                null
            )
        }
    }

    override fun onCleared() {
        logger.d("onCleared")
        if (!isEnabled) return
        unregisterAppPredictionCallback()
        appContext.unregisterReceiver(userProfileReceiver)
    }

    fun getSidebarEnabled(): Boolean =
        isEnabled && sp.getBoolean(SidebarService.SIDELINE, false)

    fun setSidebarEnabled(enabled: Boolean) =
        sp.edit {
            putBoolean(SidebarService.SIDELINE, enabled)
        }

    fun addPinnedApp(appInfo: SidebarAppInfo) {
        logger.d("addPinnedApp: $appInfo")
        pinnedKeys.update { it + appInfo.toKey() }
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertSidebarApp(appInfo.packageName, appInfo.activityName, appInfo.userId)
        }
    }

    fun deletePinnedApp(appInfo: SidebarAppInfo) {
        logger.d("deletePinnedApp: $appInfo")
        pinnedKeys.update { it - appInfo.toKey() }
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteSidebarApp(appInfo.packageName, appInfo.activityName, appInfo.userId)
        }
    }

    private fun observePinnedApps() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.getAllSidebarAppsByFlow().collect { entities ->
                pinnedKeys.value = entities
                    ?.map { appKey(it.packageName, it.activityName, it.userId) }
                    ?.toSet()
                    ?: emptySet()
            }
        }
    }

    fun isPredictedAppsEnabled(): Boolean =
        sp.getBoolean(KEY_SHOW_PREDICTED_APPS, true)

    fun setPredictedAppsEnabled(enabled: Boolean) {
        sp.edit {
            putBoolean(KEY_SHOW_PREDICTED_APPS, enabled)
        }
        if (enabled) registerAppPredictionCallback() else unregisterAppPredictionCallback()
    }

    private fun registerAppPredictionCallback() {
        if (appPredictor != null) return
        appPredictor = appPredictionManager?.createAppPredictionSession(
            AppPredictionContext.Builder(appContext)
                .setUiSurface(PREDICTION_UI_SURFACE)
                .setPredictedTargetCount(MAX_PREDICTED_APPS)
                .build()
        )?.apply {
            registerPredictionUpdates(handlerExecutor, appPredictionCallback)
            requestPredictionUpdate()
        }
    }

    private fun unregisterAppPredictionCallback() {
        appPredictor?.let { predictor ->
            predictor.unregisterPredictionUpdates(appPredictionCallback)
            predictor.destroy()
        }
        appPredictor = null
        predictedKeys.value = emptySet()
    }

    @Synchronized
    private fun initAllAppList() {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                allAppList.clear()
                userManager.getSidebarFilteredUsers().forEach { userInfo ->
                    logger.d("initAllAppList for user $userInfo")
                    val list = launcherApps.getActivityList(null, userInfo.userHandle)
                    val sidebarAppList = repository.getAllSidebarWithoutLiveData()

                    list.forEach { info ->
                        val component = info.componentName
                        if (!application.isResizeableActivity(component)) {
                            logger.d("activity not resizeable, skipped $component")
                        } else {
                            allAppList.add(
                                SidebarAppInfo(
                                    "${info.label}${userInfo.suffix}",
                                    info.getBadgedIcon(0),
                                    component.packageName,
                                    component.className,
                                    userInfo.userId,
                                    sidebarAppList?.contains(
                                        info.componentName.packageName,
                                        info.componentName.className,
                                        userInfo.userId
                                    ) ?: false
                                )
                            )
                        }
                    }
                }
            }

            _allApps.value = allAppList.sortedWith(appComparator)
            logger.d("emitted allAppList: ${_allApps.value.map { it.toKey() }}")
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                SidebarSettingsViewModel(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                )
            }
        }
    }

    private class AppComparator : Comparator<SidebarAppInfo> {
        override fun compare(p0: SidebarAppInfo, p1: SidebarAppInfo): Int {
            return compareValuesBy(
                p0,
                p1,
                { !it.isPinned },
                { it.label }
            )
        }
    }
}
