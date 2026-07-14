package com.example.viewmodel

import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.db.AppDatabase
import com.example.db.AppLockRepository
import com.example.service.AppLockService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledAppInfo(
    val appName: String,
    val packageName: String,
    val isLocked: Boolean
)

data class PermissionStates(
    val hasUsageStats: Boolean,
    val hasOverlay: Boolean,
    val hasNotification: Boolean,
    val isIgnoringBattery: Boolean
)

class AppLockViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val repository: AppLockRepository
    private val sharedPrefs = context.getSharedPreferences("035core_prefs", Context.MODE_PRIVATE)

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _permissionStates = MutableStateFlow(PermissionStates(false, false, false, false))
    val permissionStates: StateFlow<PermissionStates> = _permissionStates.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _masterPin = MutableStateFlow("1234")
    val masterPin: StateFlow<String> = _masterPin.asStateFlow()

    init {
        val database = AppDatabase.getDatabase(context)
        repository = AppLockRepository(database.lockedAppDao())
        
        _masterPin.value = sharedPrefs.getString("master_pin", "1234") ?: "1234"
        val serviceEnabled = sharedPrefs.getBoolean("service_enabled", false)
        _isServiceRunning.value = serviceEnabled
        if (serviceEnabled) {
            toggleService(true)
        }

        updatePermissionStates()
        loadApps()
    }

    fun updatePermissionStates() {
        viewModelScope.launch {
            val hasUsage = hasUsageStatsPermission()
            val hasOver = hasOverlayPermission()
            val hasNotif = hasNotificationPermission()
            val ignoringBattery = isIgnoringBatteryOptimizations()
            _permissionStates.value = PermissionStates(hasUsage, hasOver, hasNotif, ignoringBattery)
        }
    }

    private fun loadApps() {
        viewModelScope.launch {
            // Combine queried package manager apps with locked status from Room DB
            repository.allLockedAppsFlow.collect { lockedList ->
                val lockedMap = lockedList.associateBy { it.packageName }
                
                val appsList = withContext(Dispatchers.IO) {
                    val pm = context.packageManager
                    val intent = Intent(Intent.ACTION_MAIN, null).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }
                    val resolveInfos = pm.queryIntentActivities(intent, 0)
                    
                    resolveInfos.map { info ->
                        val pkgName = info.activityInfo.packageName
                        val label = info.loadLabel(pm).toString()
                        InstalledAppInfo(
                            appName = label,
                            packageName = pkgName,
                            isLocked = lockedMap.containsKey(pkgName)
                        )
                    }.distinctBy { it.packageName }.sortedBy { it.appName }
                }
                
                _installedApps.value = appsList
            }
        }
    }

    fun toggleAppLock(packageName: String, appName: String, isCurrentlyLocked: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (isCurrentlyLocked) {
                repository.unlockApp(packageName)
            } else {
                repository.lockApp(packageName, appName)
            }
        }
    }

    fun toggleService(enable: Boolean) {
        _isServiceRunning.value = enable
        sharedPrefs.edit().putBoolean("service_enabled", enable).apply()
        
        val serviceIntent = Intent(context, AppLockService::class.java)
        if (enable) {
            serviceIntent.action = AppLockService.ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } else {
            serviceIntent.action = AppLockService.ACTION_STOP
            context.startService(serviceIntent)
        }
    }

    fun setMasterPin(pin: String) {
        if (pin.length == 4) {
            _masterPin.value = pin
            sharedPrefs.edit().putString("master_pin", pin).apply()
        }
    }

    // Helper functions for checking permissions
    private fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
}
