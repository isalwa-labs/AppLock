package com.example.service

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.db.AppDatabase
import com.example.db.AppLockRepository
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class AppLockService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    
    private lateinit var windowManager: WindowManager
    private lateinit var repository: AppLockRepository
    
    // Set of currently locked package names queried from Database
    private val lockedPackages = ConcurrentHashMap.newKeySet<String>()
    
    private var trackingJob: Job? = null
    private var lastForegroundApp: String? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                tempUnlockedApps.clear()
                lastForegroundApp = null
                serviceScope.launch(Dispatchers.Main) {
                    dismissOverlay()
                }
            }
        }
    }
    
    companion object {
        const val ACTION_START = "ACTION_START_APPLOCK"
        const val ACTION_STOP = "ACTION_STOP_APPLOCK"
        
        private const val NOTIFICATION_CHANNEL_ID = "035core_lock_channel"
        private const val NOTIFICATION_ID = 3501

        // Track temp unlocked packages
        val tempUnlockedApps = ConcurrentHashMap.newKeySet<String>()
        
        @Volatile
        private var instance: AppLockService? = null
        
        fun onUnlockSuccess(packageName: String) {
            tempUnlockedApps.add(packageName)
            instance?.dismissOverlay()
        }

        fun onUnlockFailure(packageName: String) {
            // Re-trigger auth or keep overlay locked
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        val database = AppDatabase.getDatabase(this)
        repository = AppLockRepository(database.lockedAppDao())
        
        createNotificationChannel()
        startForegroundService()
        
        // Listen to database updates reactively
        serviceScope.launch {
            repository.allLockedAppsFlow.collect { list ->
                lockedPackages.clear()
                list.forEach { lockedPackages.add(it.packageName) }
            }
        }

        // Register screen off receiver for security
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            stopSelf()
        } else {
            startMonitoring()
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        val notificationIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AppLock Protection Active")
            .setContentText("Monitoring applications in real-time.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "AppLock Core Protection",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps AppLock active in background safely"
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startMonitoring() {
        if (trackingJob?.isActive == true) return
        
        trackingJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val currentApp = getForegroundPackageName()
                    if (currentApp != null && currentApp != packageName) {
                        handleAppTransition(currentApp)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(200) // 200ms reaction latency
            }
        }
    }

    private fun stopMonitoring() {
        trackingJob?.cancel()
        trackingJob = null
        dismissOverlay()
    }

    private fun getForegroundPackageName(): String? {
        val usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 15000 // Query last 15 seconds

        val events = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()
        var lastResumedApp: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastResumedApp = event.packageName
            }
        }

        if (lastResumedApp == null) {
            // Fallback to queryUsageStats
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                endTime - 60000,
                endTime
            )
            if (!stats.isNullOrEmpty()) {
                val sorted = stats.filter { it.lastTimeUsed > 0 }.maxByOrNull { it.lastTimeUsed }
                lastResumedApp = sorted?.packageName
            }
        }
        return lastResumedApp
    }

    // Window overlay state
    private var overlayView: ComposeView? = null
    private var activeOverlayPackage: String? = null
    private var lifecycleOwner: ComposeLifecycleOwner? = null

    private fun handleAppTransition(currentApp: String) {
        if (currentApp == lastForegroundApp) return
        
        val previousApp = lastForegroundApp
        lastForegroundApp = currentApp

        // Auto re-lock previous app if we left it
        if (previousApp != null && previousApp != currentApp && previousApp != packageName) {
            if (tempUnlockedApps.contains(previousApp)) {
                tempUnlockedApps.remove(previousApp)
            }
        }

        // Check if new foreground app needs locking
        if (lockedPackages.contains(currentApp) && !tempUnlockedApps.contains(currentApp)) {
            serviceScope.launch(Dispatchers.Main) {
                showLockOverlay(currentApp)
            }
        } else {
            // Dismiss overlay if user navigate away from the locked app to unlocked territory
            if (currentApp != packageName && currentApp != activeOverlayPackage) {
                serviceScope.launch(Dispatchers.Main) {
                    dismissOverlay()
                }
            }
        }
    }

    private fun showLockOverlay(packageName: String) {
        if (activeOverlayPackage == packageName && overlayView != null) return
        
        // Remove old overlay if present
        dismissOverlay()
        
        activeOverlayPackage = packageName

        val context = this
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val view = ComposeView(context)
        val owner = ComposeLifecycleOwner()
        owner.onCreate()
        owner.onStart()
        owner.onResume()

        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)

        // Render visual overlay
        view.setContent {
            AppLockOverlayContent(
                packageName = packageName,
                onPinVerified = {
                    onUnlockSuccess(packageName)
                },
                onRequestBiometric = {
                    triggerBiometrics(packageName)
                }
            )
        }

        try {
            windowManager.addView(view, layoutParams)
            overlayView = view
            lifecycleOwner = owner
            
            // Automatically launch BiometricPrompt activity on overlay presentation
            triggerBiometrics(packageName)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun triggerBiometrics(packageName: String) {
        val bioIntent = Intent(this, BiometricPromptActivity::class.java).apply {
            putExtra(BiometricPromptActivity.EXTRA_PACKAGE_NAME, packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        startActivity(bioIntent)
    }

    private fun dismissOverlay() {
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            lifecycleOwner?.apply {
                onPause()
                onStop()
                onDestroy()
            }
            overlayView = null
            lifecycleOwner = null
        }
        activeOverlayPackage = null
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        instance = null
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * A custom implementation of LifecycleOwner, ViewModelStoreOwner, and SavedStateRegistryOwner 
 * to host a fully reactive Jetpack ComposeView inside a window manager overlay.
 */
class ComposeLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val controller = SavedStateRegistryController.create(this)

    fun onCreate() {
        controller.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onStart() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    fun onResume() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onPause() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    fun onStop() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val viewModelStore: ViewModelStore = store
    override val savedStateRegistry: SavedStateRegistry = controller.savedStateRegistry
}

/**
 * Highly styled, immersive Dark Mode overlay using Material 3 UI.
 */
@Composable
fun AppLockOverlayContent(
    packageName: String,
    onPinVerified: () -> Unit,
    onRequestBiometric: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var isPinError by remember { mutableStateOf(false) }

    // Read stored passcode
    val sharedPrefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("035core_prefs", Context.MODE_PRIVATE)
    val correctPin = sharedPrefs.getString("master_pin", "1234") ?: "1234"

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0F172A) // Sleek slate-dark background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0F172A),
                            Color(0xFF020617)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                // Large styled shield lock icon
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(80.dp)
                        .background(Color(0xFF1E293B), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Secure Lock",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(40.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "AppLock Secure Overlay",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Enter secure PIN or use hardware biometric",
                    fontSize = 14.sp,
                    color = Color(0xFF94A3B8)
                )

                Spacer(modifier = Modifier.height(30.dp))

                // PIN indicator dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(4) { index ->
                        val active = index < enteredPin.length
                        val dotSize by animateDpAsState(
                            targetValue = if (active) 20.dp else 14.dp,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                        )
                        val dotColor by animateColorAsState(
                            targetValue = if (isPinError) Color(0xFFEF4444)
                            else if (active) Color(0xFF38BDF8)
                            else Color(0xFF334155)
                        )
                        Box(
                            modifier = Modifier
                                .size(dotSize)
                                .background(dotColor, CircleShape)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))

                // Keypad layout
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.widthIn(max = 280.dp)
                ) {
                    val keys = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("BIO", "0", "DEL")
                    )

                    keys.forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            row.forEach { key ->
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1.2f)
                                        .background(Color(0xFF1E293B), RoundedCornerShape(16.dp))
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable {
                                            isPinError = false
                                            when (key) {
                                                "DEL" -> {
                                                    if (enteredPin.isNotEmpty()) {
                                                        enteredPin = enteredPin.dropLast(1)
                                                    }
                                                }
                                                "BIO" -> {
                                                    onRequestBiometric()
                                                }
                                                else -> {
                                                    if (enteredPin.length < 4) {
                                                        enteredPin += key
                                                        if (enteredPin.length == 4) {
                                                            if (enteredPin == correctPin) {
                                                                onPinVerified()
                                                            } else {
                                                                isPinError = true
                                                                enteredPin = ""
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                ) {
                                    when (key) {
                                        "DEL" -> Icon(
                                            imageVector = Icons.Default.Backspace,
                                            contentDescription = "Backspace",
                                            tint = Color.White,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        "BIO" -> Icon(
                                            imageVector = Icons.Default.Fingerprint,
                                            contentDescription = "Biometrics",
                                            tint = Color(0xFF38BDF8),
                                            modifier = Modifier.size(30.dp)
                                        )
                                        else -> Text(
                                            text = key,
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
