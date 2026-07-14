package com.example.db

import kotlinx.coroutines.flow.Flow

class AppLockRepository(private val lockedAppDao: LockedAppDao) {
    val allLockedAppsFlow: Flow<List<LockedApp>> = lockedAppDao.getAllLockedAppsFlow()

    suspend fun getAllLockedApps(): List<LockedApp> {
        return lockedAppDao.getAllLockedApps()
    }

    suspend fun isAppLocked(packageName: String): Boolean {
        return lockedAppDao.isAppLocked(packageName)
    }

    suspend fun lockApp(packageName: String, appName: String) {
        lockedAppDao.insertLockedApp(LockedApp(packageName, appName))
    }

    suspend fun unlockApp(packageName: String) {
        lockedAppDao.deleteLockedApp(packageName)
    }
}
