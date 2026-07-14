package com.example.service

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.Executor

class BiometricPromptActivity : FragmentActivity() {

    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo
    private var targetPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME)

        if (targetPackage == null) {
            finish()
            return
        }

        executor = ContextCompat.getMainExecutor(this)
        
        setupBiometricPrompt()
        
        if (canAuthenticate()) {
            biometricPrompt.authenticate(promptInfo)
        } else {
            // Fallback directly to Keyguard (Device PIN/Pattern) if biometrics are not configured
            showDeviceCredentialsFallback()
        }
    }

    private fun setupBiometricPrompt() {
        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED || 
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        // User cancelled
                        notifyResult(success = false)
                    } else if (errorCode == BiometricPrompt.ERROR_HW_NOT_PRESENT || 
                        errorCode == BiometricPrompt.ERROR_HW_UNAVAILABLE || 
                        errorCode == BiometricPrompt.ERROR_NO_BIOMETRICS) {
                        // Fallback directly
                        showDeviceCredentialsFallback()
                    } else {
                        Toast.makeText(applicationContext, "Auth Error: $errString", Toast.LENGTH_SHORT).show()
                        notifyResult(success = false)
                    }
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    notifyResult(success = true)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Kept empty as biometric system handles retries natively
                }
            })

        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("035core Security")
            .setSubtitle("Verify identity to unlock application")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or 
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )

        promptInfo = builder.build()
    }

    private fun canAuthenticate(): Boolean {
        val biometricManager = BiometricManager.from(this)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or 
                             BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return biometricManager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun showDeviceCredentialsFallback() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val intent = keyguardManager.createConfirmDeviceCredentialIntent(
                "035core Security",
                "Enter your device PIN, Pattern, or Password to unlock"
            )
            if (intent != null) {
                startActivityForResult(intent, REQUEST_CODE_CONFIRM_DEVICE)
            } else {
                // Device has no lock configured at all
                notifyResult(success = true)
            }
        } else {
            notifyResult(success = true)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_CONFIRM_DEVICE) {
            if (resultCode == RESULT_OK) {
                notifyResult(success = true)
            } else {
                notifyResult(success = false)
            }
        }
    }

    private fun notifyResult(success: Boolean) {
        targetPackage?.let { pkg ->
            if (success) {
                AppLockService.onUnlockSuccess(pkg)
            } else {
                AppLockService.onUnlockFailure(pkg)
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        private const val REQUEST_CODE_CONFIRM_DEVICE = 505
    }
}
