package com.example.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LockedAppDao {
    @Query("SELECT * FROM locked_apps ORDER BY appName ASC")
    fun getAllLockedAppsFlow(): Flow<List<LockedApp>>

    @Query("SELECT * FROM locked_apps")
    suspend fun getAllLockedApps(): List<LockedApp>

    @Query("SELECT EXISTS(SELECT 1 FROM locked_apps WHERE packageName = :packageName LIMIT 1)")
    suspend fun isAppLocked(packageName: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLockedApp(app: LockedApp)

    @Query("DELETE FROM locked_apps WHERE packageName = :packageName")
    suspend fun deleteLockedApp(packageName: String)
}
