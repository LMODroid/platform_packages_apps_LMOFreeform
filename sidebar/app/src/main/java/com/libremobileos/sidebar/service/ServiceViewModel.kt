package com.libremobileos.sidebar.service

import android.app.Application
import android.app.prediction.AppPredictionContext
import android.app.prediction.AppPredictionManager
import android.app.prediction.AppPredictor
import android.app.prediction.AppTarget
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_PROFILE_AVAILABLE
import android.content.Intent.ACTION_PROFILE_UNAVAILABLE
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerExecutor
import android.os.UserHandle
import android.os.UserManager
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.libremobileos.sidebar.R
import com.libremobileos.sidebar.app.SidebarApplication
import com.libremobileos.sidebar.bean.AppInfo
import com.libremobileos.sidebar.room.DatabaseRepository
import com.libremobileos.sidebar.room.SidebarAppsEntity
import com.libremobileos.sidebar.ui.sidebar.SidebarSettingsActivity
import com.libremobileos.sidebar.utils.Logger
import com.libremobileos.sidebar.utils.contains
import com.libremobileos.sidebar.utils.getBadgedIcon
import com.libremobileos.sidebar.utils.getInfo
import com.libremobileos.sidebar.utils.isResizeableActivity
import com.libremobileos.sidebar.utils.isSidebarUserAllowed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * @author KindBrave
 * @since 2023/8/25
 */
class ServiceViewModel(private val application: Application): AndroidViewModel(application) {
    private val logger = Logger("ServiceViewModel")

    private val repository = DatabaseRepository(application)

    val sidebarAppListFlow: StateFlow<List<AppInfo>>
        get() = _sidebarAppList.asStateFlow()
    private val _sidebarAppList = MutableStateFlow<List<AppInfo>>(emptyList())

    private val predictedAppList = MutableStateFlow<List<AppInfo>>(emptyList())

    private val appContext = application.applicationContext
    private val launcherApps = application.getSystemService(Context.LAUNCHER_APPS_SERVICE)!! as LauncherApps
    private val appPredictionManager = application.getSystemService(AppPredictionManager::class.java)
    private val userManager = application.getSystemService(UserManager::class.java)!!
    private val sharedPrefs = appContext.getSharedPreferences(SidebarApplication.CONFIG, Context.MODE_PRIVATE)

    private var appPredictor: AppPredictor? = null
    private val handlerExecutor = HandlerExecutor(Handler())
    private var callbacksRegistered = false
    private var showPredictedApps = sharedPrefs.getBoolean(KEY_SHOW_PREDICTED_APPS, true)
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                registerAppPredictionCallback()
            } else {
                unregisterAppPredictionCallback()
                predictedAppList.value = emptyList()
            }
        }

    val allAppActivity = AppInfo(
        "",
        AppCompatResources.getDrawable(appContext, R.drawable.ic_all)!!,
        ALL_APP_PACKAGE,
        ALL_APP_ACTIVITY,
        0
    )

    private val launcherAppsCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            logger.d("onPackageRemoved: $packageName")
            _sidebarAppList.value.getInfo(packageName, user)?.let {
                repository.deleteSidebarApp(it.packageName, it.activityName, it.userId)
            }
        }

        override fun onPackageAdded(packageName: String, user: UserHandle) {

        }

        override fun onPackageChanged(packageName: String, user: UserHandle) {
            logger.d("onPackageChanged: $packageName")
            val appInfo = application.packageManager.getApplicationInfo(packageName, 0)
            if (!appInfo.enabled) {
                onPackageRemoved(packageName, user)
            }
        }

        override fun onPackagesAvailable(
            packageNames: Array<out String>?,
            user: UserHandle?,
            replacing: Boolean
        ) {
            logger.d("onPackagesAvailable: ${packageNames.contentToString()}, $user")
        }

        override fun onPackagesUnavailable(
            packageNames: Array<out String>?,
            user: UserHandle?,
            replacing: Boolean
        ) {
            logger.d("onPackagesUnavailable: ${packageNames.contentToString()}, $user")
        }
    }

    private val appPredictionCallback = object : AppPredictor.Callback {
        override fun onTargetsAvailable(targets: List<AppTarget>) {
            if (!showPredictedApps) return
            logger.d("appPredictionCallback targets: ${targets.size}")
            predictedAppList.value = targets
                .take(MAX_PREDICTED_APPS)
                .mapNotNull { target ->
                    runCatching {
                        val info = application.packageManager.getApplicationInfo(target.packageName, PackageManager.GET_ACTIVITIES)
                        val launchIntent = application.packageManager.getLaunchIntentForPackage(target.packageName)
                        val component = launchIntent!!.component!!
                        val userId = target.user.identifier
                        if (!application.isResizeableActivity(component)) {
                            logger.d("appPredictionCallback: activity is not resizeable, skipped $target")
                            null
                        } else {
                            AppInfo(
                                info.loadLabel(application.packageManager).toString(),
                                application.getBadgedIcon(info, target.user),
                                info.packageName,
                                component.className,
                                userId,
                                isPinned = false
                            )
                        }
                    }.onFailure { e ->
                        logger.e("failed to add $target: ", e)
                    }.getOrNull()
                }
        }
    }

    private val userProfileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val user: UserHandle = intent.getParcelableExtra(Intent.EXTRA_USER) ?: return
            val userId = user.identifier
            logger.d("userProfileReceiver received ${intent.action} $user")
            when (intent.action) {
                ACTION_PROFILE_AVAILABLE -> {
                    val sidebarApps = repository.getAllSidebarWithoutLiveData() ?: return
                    val newApps = sidebarApps
                        .filter { it.userId == userId }
                        .mapNotNull { entity ->
                            runCatching { entity.toAppInfo() }
                                .onFailure { e ->
                                    logger.e("failed to add entity $entity: $e" )
                                }
                                .getOrNull()
                        }
                    logger.d("userProfileReceiver added apps: $newApps")
                    // add them at the top
                    _sidebarAppList.value = newApps + _sidebarAppList.value
                }
                ACTION_PROFILE_UNAVAILABLE -> {
                    _sidebarAppList.value = _sidebarAppList.value
                        .filter { it.userId != userId }
                }
            }
        }
    }

    private val sharedPrefsListener = object : OnSharedPreferenceChangeListener {
        override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
            when (key) {
                KEY_SHOW_PREDICTED_APPS -> {
                    showPredictedApps = sharedPrefs.getBoolean(KEY_SHOW_PREDICTED_APPS, true)
                }
            }
        }
    }

    companion object {
        private const val ALL_APP_PACKAGE = "com.libremobileos.sidebar"
        private const val ALL_APP_ACTIVITY = "com.libremobileos.sidebar.ui.all_app.AllAppActivity"
        const val MAX_PREDICTED_APPS = 6
        const val PREDICTION_UI_SURFACE = "hotseat"
        const val KEY_SHOW_PREDICTED_APPS = "sidebar_show_predicted_apps"
    }

    init {
        logger.d("init")
    }

    fun registerCallbacks() {
        if (callbacksRegistered) return
        logger.d("registerCallbacks")
        initSidebarAppList()
        launcherApps.registerCallback(launcherAppsCallback)
        if (showPredictedApps) registerAppPredictionCallback()
        sharedPrefs.registerOnSharedPreferenceChangeListener(sharedPrefsListener)
        registerUserProfileReceiver()
        callbacksRegistered = true
    }

    fun unregisterCallbacks() {
        if (!callbacksRegistered) return
        logger.d("unregisterCallbacks")
        launcherApps.unregisterCallback(launcherAppsCallback)
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(sharedPrefsListener)
        if (showPredictedApps) unregisterAppPredictionCallback()
        appContext.unregisterReceiver(userProfileReceiver)
        viewModelScope.coroutineContext.cancelChildren()
        callbacksRegistered = false
    }

    fun destroy() {
        logger.d("destroy")
        runCatching { viewModelScope.cancel() }
    }

    fun openSidebarSettings() {
        appContext.startActivity(
            Intent(appContext, SidebarSettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun registerAppPredictionCallback() {
        if (appPredictionManager == null) {
            logger.e("appPredictionManager is null!")
            return
        }
        appPredictor = appPredictionManager.createAppPredictionSession(
            AppPredictionContext.Builder(appContext)
                .setUiSurface(PREDICTION_UI_SURFACE)
                .setPredictedTargetCount(MAX_PREDICTED_APPS)
                .build()
        ).apply {
            registerPredictionUpdates(handlerExecutor, appPredictionCallback)
            requestPredictionUpdate()
        }
    }

    private fun unregisterAppPredictionCallback() {
        appPredictor?.unregisterPredictionUpdates(appPredictionCallback)
    }

    private fun registerUserProfileReceiver() {
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

    private fun initSidebarAppList() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.getAllSidebarAppsByFlow()
                .combine(predictedAppList) { sidebarApps, predictedApps ->
                    mutableListOf<AppInfo>().apply {
                        logger.d("initSidebarAppList: sidebarApps=$sidebarApps predictedApps=$predictedApps")
                        // first add the pinned apps
                        sidebarApps?.forEach { entity ->
                            if (!userManager.isSidebarUserAllowed(entity.userId)) {
                                logger.w("initSidebarAppList: userid not allowed: $entity")
                                return@forEach
                            }
                            runCatching {
                                add(entity.toAppInfo())
                            }.onFailure { e ->
                                logger.w("initSidebarAppList: removing $entity: $e")
                                repository.deleteSidebarApp(entity.packageName, entity.activityName, entity.userId)
                            }
                        }
                        // then the predicted apps
                        addAll(
                            predictedApps.filter { sidebarApps?.contains(it)?.not() ?: true }
                        )
                    }
                }
                .collect { sidebarAppList ->
                    logger.d("initSidebarAppList: combinedList=$sidebarAppList")
                    _sidebarAppList.value = sidebarAppList.toList()
                }
        }
    }

    private fun SidebarAppsEntity.toAppInfo(): AppInfo {
        if (!application.isResizeableActivity(packageName, activityName)) {
            throw Exception("activity is not resizeable")
        }
        val info = application.packageManager.getApplicationInfo(
            packageName,
            PackageManager.GET_ACTIVITIES
        )
        if (!info.enabled) {
            throw Exception("package is disabled.")
        }
        return AppInfo(
            info.loadLabel(application.packageManager).toString(),
            application.getBadgedIcon(info, userId),
            packageName,
            activityName,
            userId
        )
    }
}

