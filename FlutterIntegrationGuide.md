# 035core Flutter AppLock Integration Guide

This guide provides the complete production-grade architecture to bridge the native **035core Kotlin Background Service** with your **Flutter UI**. 

---

## 1. Kotlin-Side Method Channel Setup
To bridge Flutter with your background services, place the following class in your Android project under `android/app/src/main/kotlin/com/example/flutter/FlutterMethodChannelBridge.kt`.

```kotlin
package com.example.flutter

import android.content.Context
import android.content.Intent
import com.example.db.AppDatabase
import com.example.db.AppLockRepository
import com.example.service.AppLockService
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*

class FlutterMethodChannelBridge : FlutterPlugin, MethodChannel.MethodCallHandler {

    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private lateinit var repository: AppLockRepository
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "com.aistudio.applock/bridge")
        channel.setMethodCallHandler(this)
        
        val db = AppDatabase.getDatabase(context)
        repository = AppLockRepository(db.lockedAppDao())
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isServiceRunning" -> {
                val prefs = context.getSharedPreferences("035core_prefs", Context.MODE_PRIVATE)
                val enabled = prefs.getBoolean("service_enabled", false)
                result.success(enabled)
            }
            "toggleService" -> {
                val enable = call.argument<Boolean>("enable") ?: false
                toggleServiceIntent(enable)
                result.success(true)
            }
            "getLockedApps" -> {
                scope.launch {
                    val apps = withContext(Dispatchers.IO) {
                        repository.getAllLockedApps().map { it.packageName }
                    }
                    result.success(apps)
                }
            }
            "lockApp" -> {
                val pkg = call.argument<String>("packageName") ?: ""
                val label = call.argument<String>("appName") ?: "Locked App"
                scope.launch(Dispatchers.IO) {
                    repository.lockApp(pkg, label)
                    withContext(Dispatchers.Main) { result.success(true) }
                }
            }
            "unlockApp" -> {
                val pkg = call.argument<String>("packageName") ?: ""
                scope.launch(Dispatchers.IO) {
                    repository.unlockApp(pkg)
                    withContext(Dispatchers.Main) { result.success(true) }
                }
            }
            "setMasterPin" -> {
                val pin = call.argument<String>("pin") ?: "1234"
                val prefs = context.getSharedPreferences("035core_prefs", Context.MODE_PRIVATE)
                prefs.edit().putString("master_pin", pin).apply()
                result.success(true)
            }
            else -> result.notImplemented()
        }
    }

    private fun toggleServiceIntent(enable: Boolean) {
        val prefs = context.getSharedPreferences("035core_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("service_enabled", enable).apply()
        
        val serviceIntent = Intent(context, AppLockService::class.java).apply {
            action = if (enable) AppLockService.ACTION_START else AppLockService.ACTION_STOP
        }
        if (enable) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } else {
            context.startService(serviceIntent)
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        scope.cancel()
    }
}
```

Register this plugin in your `MainActivity.kt`'s Flutter initialization block:
```kotlin
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import com.example.flutter.FlutterMethodChannelBridge

class MainActivity: FlutterFragmentActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        flutterEngine.plugins.add(FlutterMethodChannelBridge())
    }
}
```

---

## 2. Flutter Dart-Side Bridge Wrapper
In Flutter, manage AppLock settings using this typed singleton service:

```dart
import 'package:flutter/services.dart';

class AppLockBridge {
  static const MethodChannel _channel = MethodChannel('com.aistudio.applock/bridge');

  // Check if AppLock background monitoring is currently active
  static Future<bool> isServiceRunning() async {
    return await _channel.invokeMethod<bool>('isServiceRunning') ?? false;
  }

  // Toggle the background monitoring service
  static Future<void> toggleService(bool enable) async {
    await _channel.invokeMethod('toggleService', {'enable': enable});
  }

  // Retrieve the list of package names locked in Room DB
  static Future<List<String>> getLockedApps() async {
    final List<dynamic>? list = await _channel.invokeMethod<List<dynamic>>('getLockedApps');
    return list?.map((e) => e.toString()).toList() ?? [];
  }

  // Add a package to the locked list
  static Future<void> lockApp(String packageName, String appName) async {
    await _channel.invokeMethod('lockApp', {
      'packageName': packageName,
      'appName': appName,
    });
  }

  // Remove a package from the locked list
  static Future<void> unlockApp(String packageName) async {
    await _channel.invokeMethod('unlockApp', {'packageName': packageName});
  }

  // Set the fallback passcode PIN
  static Future<void> setMasterPin(String pin) async {
    await _channel.invokeMethod('setMasterPin', {'pin': pin});
  }
}
```

---

## 3. Flutter Biometric Authentication & Fallback Workflow
For the Flutter lock overlay page, utilize the `local_auth` library to invoke hardware verification with automated OS fallback (PIN/Pattern/Password).

### Install Dependency
```yaml
dependencies:
  local_auth: ^2.2.0
```

### Integration Workflow Block
```dart
import 'package:flutter/material.dart';
import 'package:local_auth/local_auth.dart';
import 'package:local_auth_android/local_auth_android.dart';

class BiometricLockOverlay extends StatefulWidget {
  final String targetPackage;
  final VoidCallback onUnlockSuccess;

  const BiometricLockOverlay({
    Key? key,
    required this.targetPackage,
    required this.onUnlockSuccess,
  }) : super(key: key);

  @override
  State<BiometricLockOverlay> createState() => _BiometricLockOverlayState();
}

class _BiometricLockOverlayState extends State<BiometricLockOverlay> {
  final LocalAuthentication _auth = LocalAuthentication();
  bool _isAuthenticating = false;

  @override
  void initState() {
    super.initState();
    // Auto-trigger biometrics immediately on screen overlay display
    _triggerBiometrics();
  }

  Future<void> _triggerBiometrics() async {
    if (_isAuthenticating) return;

    // Check device capabilities
    final bool canAuthenticateWithBiometrics = await _auth.canCheckBiometrics;
    final bool isDeviceSupported = await _auth.isDeviceSupported();

    if (!canAuthenticateWithBiometrics && !isDeviceSupported) {
      _showPasscodeFallback();
      return;
    }

    setState(() {
      _isAuthenticating = true;
    });

    try {
      final bool authenticated = await _auth.authenticate(
        localizedReason: '035core Protection: Please authenticate to unlock',
        options: const AuthenticationOptions(
          stickyAuth: true,
          biometricOnly: false, // Set to false to allow automated system PIN/Pattern fallback
        ),
        authMessages: const <AuthMessages>[
          AndroidAuthMessages(
            signInTitle: '035core Security',
            deviceCredentialsRequiredTitle: 'Authentication Required',
            deviceCredentialsSetupDescription: 'Please setup biometrics or PIN first.',
          ),
        ],
      );

      if (authenticated) {
        widget.onUnlockSuccess();
      }
    } on PlatformException catch (e) {
      debugPrint("Authentication Error: ${e.message}");
      _showPasscodeFallback();
    } finally {
      setState(() {
        _isAuthenticating = false;
      });
    }
  }

  void _showPasscodeFallback() {
    // Custom fallback to custom 4-digit PIN pad UI
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Please use manual PIN Pad unlock')),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0F172A),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(
              Icons.lock,
              size: 80,
              color: Color(0xFF38BDF8),
            ),
            const SizedBox(height: 24),
            const Text(
              '035core Locked',
              style: TextStyle(
                fontSize: 24,
                fontWeight: FontWeight.bold,
                color: Colors.white,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              'Security lock for: ${widget.targetPackage}',
              style: const TextStyle(color: Color(0xFF94A3B8)),
            ),
            const SizedBox(height: 48),
            ElevatedButton.icon(
              onPressed: _triggerBiometrics,
              icon: const Icon(Icons.fingerprint),
              label: const Text('Tap to Unlock'),
              style: ElevatedButton.styleFrom(
                backgroundColor: const Color(0xFF1E293B),
                foregroundColor: const Color(0xFF38BDF8),
                padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
                shape: RoundedCornerShape(12),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
```
