package com.example.socketclient

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
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
        const val SERVER_IP = "192.168.0.179"
        const val SERVER_PORT = 54835
        const val CHANNEL_ID = "SocketServiceChannel"
        const val NOTIFICATION_ID = 2
        private const val TAG = "SocketService"
    }

    private val DELIMITER = "\nJ\\."
    private var socket: Socket? = null
    // DataOutputStream is convenient for writing binary ints and bytes
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
        startForegroundServiceWithNotification()

        if (intent?.hasExtra("mediaProjectionData") == true) {
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
        }

        // Start (or restart) the connection thread
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
                        socket?.soTimeout = 0 // blocking read
                        dataOut = DataOutputStream(BufferedOutputStream(socket!!.getOutputStream()))
                        Log.d(TAG, "Connected to server")
                        // start listening on this socket
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

                // if disconnected, wait a bit before retrying to avoid busy-loop
                if (shouldRun && (socket == null || socket?.isClosed == true)) {
                    try { Thread.sleep(2000) } catch (_: InterruptedException) {}
                }
            }
        }
    }

    private fun listenForCommands() {
        try {
            val input = socket?.getInputStream() ?: run {
                Log.e(TAG, "Input stream is null")
                return
            }
            val reader = BufferedInputStream(input)
            val buffer = ByteArray(8192)
            val sb = StringBuilder()

            while (shouldRun && socket != null && socket!!.isConnected) {
                val length = try {
                    reader.read(buffer)
                } catch (e: IOException) {
                    Log.e(TAG, "Error reading from input", e)
                    -1
                }

                if (length > 0) {
                    val part = String(buffer, 0, length)
                    sb.append(part)
                    // process whenever delimiter maybe present (commands may be single or contain DELIMITER)
                    var full = sb.toString()
                    // split by delimiter but keep delimiter for parsing consistency if needed
                    if (full.contains(DELIMITER)) {
                        val pieces = full.split(DELIMITER)
                        // last piece may be incomplete -> keep it in sb
                        val incomplete = if (!full.endsWith(DELIMITER)) pieces.last() else ""
                        val toProcess = if (incomplete.isEmpty()) pieces else pieces.dropLast(1)
                        for (p in toProcess) {
                            val cmd = p.trim()
                            if (cmd.isNotEmpty()) {
                                Log.d(TAG, "Received command chunk: $cmd")
                                handleCommand(cmd)
                            }
                        }
                        sb.setLength(0)
                        sb.append(incomplete)
                    } else {
                        // no delimiter yet, keep accumulating
                    }
                } else if (length == -1) {
                    Log.d(TAG, "Server closed connection (read returned -1)")
                    break
                } else {
                    // length == 0 ? continue
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error in listenForCommands", e)
        } finally {
            safeCloseSocket()
        }
    }

    private fun handleCommand(data: String) {
        // data here is the chunk before delimiter
        // treat the first token as command, rest as args separated by DELIMITER if any
        try {
            Log.d(TAG, "handleCommand raw: $data")
            val tokens = data.split(DELIMITER)
            val cmd = tokens[0].trim()
            when (cmd) {
                "~" -> sendData("~$DELIMITER${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}$DELIMITER")
                "!" -> {
                    val size = getScreenSize()
                    sendData("!$DELIMITER${size.first}$DELIMITER${size.second}$DELIMITER")
                }
                "LOC" -> fetchAndSendLocation()
                "@" -> startScreenCapture()
                "close" -> {
                    sendData("CLOSING$DELIMITER")
                    stopSelf()
                }
                else -> sendData("ERR${DELIMITER}Unknown command:$cmd$DELIMITER")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception handling command", e)
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
            } else {
                sendData("LOC${DELIMITER}Permission denied$DELIMITER")
            }
        } catch (e: SecurityException) {
            sendData("LOC${DELIMITER}Permission denied$DELIMITER")
        } catch (t: Throwable) {
            sendData("LOC${DELIMITER}Error fetching location: ${t.message}$DELIMITER")
        }
    }

    private fun startScreenCapture() {
        if (mediaProjection == null) {
            sendData("@${DELIMITER}MediaProjection not available$DELIMITER")
            Log.w(TAG, "MediaProjection not available when asked to start capture")
            return
        }

        val size = getScreenSize()
        val width = size.first
        val height = size.second

        // clean previous if any
        try {
            virtualDisplay?.release()
            imageReader?.close()
        } catch (_: Exception) {}

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        // flag constant available on newer APIs, for older just 0
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
        } else 0

        try {
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "SocketScreenCapture",
                width,
                height,
                resources.displayMetrics.densityDpi,
                flags,
                imageReader?.surface,
                null,
                null
            )
        } catch (t: Throwable) {
            Log.e(TAG, "createVirtualDisplay failed", t)
            sendData("@${DELIMITER}Failed to create virtual display: ${t.message}$DELIMITER")
            return
        }

        sendData("@${DELIMITER}Screen capture started$DELIMITER")

        // start image loop
        thread {
            try {
                while (mediaProjection != null && shouldRun) {
                    val image = try {
                        imageReader?.acquireLatestImage()
                    } catch (t: Throwable) {
                        Log.e(TAG, "acquireLatestImage error", t)
                        null
                    }

                    if (image == null) {
                        // nothing new, short sleep
                        try { Thread.sleep(50) } catch (_: InterruptedException) {}
                        continue
                    }

                    try {
                        val planes = image.planes
                        if (planes.isEmpty()) {
                            image.close()
                            continue
                        }
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width
                        val bitmap = Bitmap.createBitmap(
                            width + rowPadding / pixelStride,
                            height,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)
                        image.close()

                        val baos = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 30, baos)
                        val data = baos.toByteArray()
                        baos.close()
                        bitmap.recycle()

                        // send image length + data atomically
                        sendBytesWithLength(data)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Error processing image", t)
                    }
                    // throttle to ~10fps
                    try { Thread.sleep(100) } catch (_: InterruptedException) {}
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Screen capture thread died", t)
            }
        }
    }

    private fun sendBytesWithLength(data: ByteArray) {
        try {
            val out = dataOut ?: run {
                Log.e(TAG, "dataOut is null, cannot send image")
                return
            }
            synchronized(out) {
                try {
                    // write length (big-endian)
                    out.writeInt(data.size)
                    out.write(data)
                    out.flush()
                } catch (se: SocketException) {
                    Log.e(TAG, "Socket exception while sending bytes", se)
                    safeCloseSocket()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to sendBytesWithLength", t)
        }
    }

    private fun sendData(message: String) {
        thread {
            val out = dataOut
            if (out == null) {
                Log.e(TAG, "sendData failed: socket/out is null. msg=$message")
                return@thread
            }
            synchronized(out) {
                try {
                    out.write(message.toByteArray())
                    out.flush()
                    Log.d(TAG, "Sent: $message")
                } catch (se: SocketException) {
                    Log.e(TAG, "Socket exception while sending data", se)
                    safeCloseSocket()
                } catch (io: IOException) {
                    Log.e(TAG, "IO error while sending data", io)
                    safeCloseSocket()
                }
            }
        }
    }

    private fun safeCloseSocket() {
        try {
            dataOut?.close()
        } catch (_: Exception) {}
        try {
            socket?.close()
        } catch (_: Exception) {}
        dataOut = null
        socket = null
    }

    override fun onDestroy() {
        super.onDestroy()
        shouldRun = false
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        try {
            imageReader?.close()
        } catch (_: Exception) {}
        safeCloseSocket()
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
