package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.AppLockViewModel
import com.example.viewmodel.InstalledAppInfo
import com.example.viewmodel.PermissionStates

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                AppLockDashboardScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLockDashboardScreen(
    viewModel: AppLockViewModel = viewModel()
) {
    val context = LocalContext.current
    val installedApps by viewModel.installedApps.collectAsState()
    val permissionStates by viewModel.permissionStates.collectAsState()
    val isServiceRunning by viewModel.isServiceRunning.collectAsState()
    val masterPin by viewModel.masterPin.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf(0) } // 0: Apps, 1: Settings
    var showPinDialog by remember { mutableStateOf(false) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.updatePermissionStates()
    }

    // Run permission check on resume
    DisposableEffect(Unit) {
        viewModel.updatePermissionStates()
        onDispose {}
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF0F172A),
                contentColor = Color.White
            ) {
                NavigationBarItem(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    icon = { Icon(Icons.Default.Apps, contentDescription = "Apps") },
                    label = { Text("Applications") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF38BDF8),
                        selectedTextColor = Color(0xFF38BDF8),
                        unselectedIconColor = Color(0xFF94A3B8),
                        unselectedTextColor = Color(0xFF94A3B8),
                        indicatorColor = Color(0xFF1E293B)
                    )
                )
                NavigationBarItem(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF38BDF8),
                        selectedTextColor = Color(0xFF38BDF8),
                        unselectedIconColor = Color(0xFF94A3B8),
                        unselectedTextColor = Color(0xFF94A3B8),
                        indicatorColor = Color(0xFF1E293B)
                    )
                )
            }
        },
        containerColor = Color(0xFF020617)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF020617),
                            Color(0xFF0F172A)
                        )
                    )
                )
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            AnimatedContent(
                targetState = activeTab,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(220)) + slideInHorizontally(animationSpec = tween(220), initialOffsetX = { x -> if (targetState > initialState) x else -x }))
                        .togetherWith(fadeOut(animationSpec = tween(220)) + slideOutHorizontally(animationSpec = tween(220), targetOffsetX = { x -> if (targetState > initialState) -x else x }))
                },
                label = "tab_transition",
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) { targetTab ->
                if (targetTab == 0) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // App Search Bar
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search installed applications...", color = Color(0xFF64748B)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF38BDF8),
                                unfocusedBorderColor = Color(0xFF334155)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color(0xFF64748B)) },
                            singleLine = true
                        )

                        // Quick Service Status Info Banner
                        if (!isServiceRunning) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 10.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Warning, contentDescription = "Warning", tint = Color.White)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text("Protection is Disabled", fontWeight = FontWeight.Bold, color = Color.White)
                                        Text("Toggle protection service in the Settings tab.", fontSize = 12.sp, color = Color(0xFFFECACA))
                                    }
                                }
                            }
                        }

                        // Apps List
                        val filteredApps = installedApps.filter {
                            it.appName.contains(searchQuery, ignoreCase = true) || 
                            it.packageName.contains(searchQuery, ignoreCase = true)
                        }

                        if (filteredApps.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Inbox, contentDescription = "No apps", tint = Color(0xFF334155), modifier = Modifier.size(60.dp))
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("No Applications Found", color = Color(0xFF64748B), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(horizontal = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(filteredApps) { app ->
                                    AppItemRow(
                                        app = app,
                                        onLockToggled = {
                                            viewModel.toggleAppLock(app.packageName, app.appName, app.isLocked)
                                        }
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // SETTINGS & PERMISSIONS TAB
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Global Guard Controller Card
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, Color(0xFF1E293B))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(20.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "AppLock Protection",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp,
                                            color = Color.White
                                        )
                                        Text(
                                            text = if (isServiceRunning) "Running persistently in background" else "Security engine is turned off",
                                            fontSize = 13.sp,
                                            color = if (isServiceRunning) Color(0xFF10B981) else Color(0xFF94A3B8)
                                        )
                                    }
                                    Switch(
                                        checked = isServiceRunning,
                                        onCheckedChange = { viewModel.toggleService(it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = Color(0xFF38BDF8),
                                            uncheckedThumbColor = Color(0xFF64748B),
                                            uncheckedTrackColor = Color(0xFF1E293B)
                                        )
                                    )
                                }
                            }
                        }

                        // Passcode PIN Config Card
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, Color(0xFF1E293B))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(20.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "Master Unlock PIN",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            color = Color.White
                                        )
                                        var isPinVisible by remember { mutableStateOf(false) }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "Current: ${if (isPinVisible) masterPin else "••••"}",
                                                fontSize = 13.sp,
                                                color = Color(0xFF38BDF8)
                                            )
                                            IconButton(
                                                onClick = { isPinVisible = !isPinVisible },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (isPinVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                    contentDescription = "Toggle PIN visibility",
                                                    tint = Color(0xFF94A3B8),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                    Button(
                                        onClick = { showPinDialog = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Change PIN", color = Color.White)
                                    }
                                }
                            }
                        }

                        // System Permissions Control Panel Header
                        item {
                            Text(
                                text = "Core Requirements",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color.White,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }

                        // Usage Stats Card
                        item {
                            PermissionRowCard(
                                title = "Usage Access",
                                description = "Required to detect when locked applications are launched.",
                                granted = permissionStates.hasUsageStats,
                                onRequest = {
                                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                }
                            )
                        }

                        // Overlay Card
                        item {
                            PermissionRowCard(
                                title = "Display Over Other Apps",
                                description = "Required to draw the secure authentication overlay screen.",
                                granted = permissionStates.hasOverlay,
                                onRequest = {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    ).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                }
                            )
                        }

                        // Battery ignoring Card
                        item {
                            PermissionRowCard(
                                title = "Ignore Battery Optimizations",
                                description = "Prevents Android OS from killing the lock tracker in background.",
                                granted = permissionStates.isIgnoringBattery,
                                onRequest = {
                                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                }
                            )
                        }

                        // Notification Card
                        item {
                            PermissionRowCard(
                                title = "Notification Permission",
                                description = "Required on Android 13+ to host the persistent foreground service.",
                                granted = permissionStates.hasNotification,
                                onRequest = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Passcode edit dialogue
    if (showPinDialog) {
        var tempPin by remember { mutableStateOf("") }
        var isInputError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text("Set Master Passcode PIN", color = Color.White) },
            text = {
                Column {
                    Text("Enter a secure 4-digit PIN to use as the unlock fallback passcode.", color = Color(0xFF94A3B8))
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = tempPin,
                        onValueChange = {
                            if (it.length <= 4 && it.all { char -> char.isDigit() }) {
                                tempPin = it
                                isInputError = false
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        isError = isInputError,
                        singleLine = true,
                        placeholder = { Text("4 Digit PIN") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF334155)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isInputError) {
                        Text("PIN must be exactly 4 digits long.", color = Color(0xFFEF4444), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tempPin.length == 4) {
                            viewModel.setMasterPin(tempPin)
                            showPinDialog = false
                            Toast.makeText(context, "Unlock PIN updated successfully", Toast.LENGTH_SHORT).show()
                        } else {
                            isInputError = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8))
                ) {
                    Text("Save PIN", color = Color.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) {
                    Text("Cancel", color = Color(0xFF94A3B8))
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }
}

@Composable
fun AppItemRow(
    app: InstalledAppInfo,
    onLockToggled: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onLockToggled),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Placeholder beautiful icon
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .background(Color(0xFF1E293B), RoundedCornerShape(10.dp))
                ) {
                    Icon(
                        imageVector = if (app.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = "App Icon Placeholder",
                        tint = if (app.isLocked) Color(0xFFEF4444) else Color(0xFF10B981),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = app.appName,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 15.sp
                    )
                    Text(
                        text = app.packageName,
                        color = Color(0xFF64748B),
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }
            Switch(
                checked = app.isLocked,
                onCheckedChange = { onLockToggled() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFFEF4444),
                    uncheckedThumbColor = Color(0xFF64748B),
                    uncheckedTrackColor = Color(0xFF1E293B)
                )
            )
        }
    }
}

@Composable
fun PermissionRowCard(
    title: String,
    description: String,
    granted: Boolean,
    onRequest: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (granted) Color(0xFF1E293B) else Color(0xFF7F1D1D))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        contentDescription = if (granted) "Granted" else "Missing",
                        tint = if (granted) Color(0xFF10B981) else Color(0xFFEF4444),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (granted) "Granted" else "Missing",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (granted) Color(0xFF10B981) else Color(0xFFEF4444)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                color = Color(0xFF94A3B8)
            )
            if (!granted) {
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = onRequest,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA580C)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Permission", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
