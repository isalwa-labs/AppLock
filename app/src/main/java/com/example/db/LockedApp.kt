package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "locked_apps")
data class LockedApp(
    @PrimaryKey val packageName: String,
    val appName: String,
    val lockedAt: Long = System.currentTimeMillis()
)
