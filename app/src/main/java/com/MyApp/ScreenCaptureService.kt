package com.example.socketclient

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ScreenCaptureService : Service() {

    companion object {
        const val SERVER_IP = "102.176.65.253"
        const val SERVER_PORT = 45655
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "screen_capture_channel"
        private const val TAG = "ScreenCaptureService"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private val frameQueue = LinkedBlockingQueue<ByteArray>(8)
    private var senderThread: Thread? = null
    private val FRAME_INTERVAL_MS = 200L
    private var lastFrameTime = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "ScreenCaptureService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            Log.d(TAG, "ScreenCaptureService started")

            if (intent == null) {
                Log.e(TAG, "Null intent received — stopping service safely")
                stopSelf()
                return START_NOT_STICKY
            }

            val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("data", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("data")
            }

            if (resultData == null) {
                Log.e(TAG, "MediaProjection data missing — stopping service")
                stopSelf()
                return START_NOT_STICKY
            }

            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, resultData)

            if (mediaProjection == null) {
                Log.e(TAG, "Failed to create MediaProjection — stopping service")
                stopSelf()
                return START_NOT_STICKY
            }

            startForeground(NOTIFICATION_ID, createNotification())
            startHandlerThread()
            startSenderThread()
            startScreenCapture()

        } catch (e: Exception) {
            Log.e(TAG, "Service failed to start: ${e.message}", e)
            stopSelf()
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Capture")
            .setContentText("Capturing screen...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun startHandlerThread() {
        handlerThread = HandlerThread("ImageListenerThread").apply { start() }
        handler = Handler(handlerThread!!.looper)
    }

    private fun startSenderThread() {
        senderThread = Thread {
            var socket: Socket? = null
            var out: OutputStream? = null
            while (!Thread.currentThread().isInterrupted) {
                try {
                    if (socket == null || socket.isClosed || !socket.isConnected) {
                        try {
                            socket = Socket()
                            socket.connect(InetSocketAddress(SERVER_IP, SERVER_PORT), 5000)
                            out = socket.getOutputStream()
                            Log.d(TAG, "Connected to server")
                        } catch (e: Exception) {
                            Log.e(TAG, "Connect failed: ${e.message}")
                            try { socket?.close() } catch (_: Exception) {}
                            socket = null
                            out = null
                            Thread.sleep(2000)
                            continue
                        }
                    }

                    val frame = frameQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    try {
                        sendFrame(out, frame)
                        Log.d(TAG, "Frame sent: ${frame.size} bytes")
                    } catch (e: Exception) {
                        Log.e(TAG, "Send failed: ${e.message}")
                        try { socket?.close() } catch (_: Exception) {}
                        socket = null
                        out = null
                        frameQueue.offer(frame, 500, TimeUnit.MILLISECONDS)
                        Thread.sleep(1000)
                    }

                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    Log.e(TAG, "Sender error: ${e.message}", e)
                }
            }
            try { socket?.close() } catch (_: Exception) {}
        }
        senderThread!!.start()
    }

    private fun sendFrame(out: OutputStream?, frame: ByteArray) {
        if (out == null) return
        try {
            val size = frame.size
            val header = byteArrayOf(
                ((size shr 24) and 0xFF).toByte(),
                ((size shr 16) and 0xFF).toByte(),
                ((size shr 8) and 0xFF).toByte(),
                (size and 0xFF).toByte()
            )
            out.write(header)
            out.write(frame)
            out.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing frame: ${e.message}", e)
        }
    }

    private fun startScreenCapture() {
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null, cannot start capture")
            stopSelf()
            return
        }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            try {
                val now = System.currentTimeMillis()
                if (now - lastFrameTime < FRAME_INTERVAL_MS) {
                    reader.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }
                lastFrameTime = now

                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val frameBytes = processImage(image)
                frameBytes?.let {
                    frameQueue.offer(it, 500, TimeUnit.MILLISECONDS)
                }
                image.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing frame: ${e.message}", e)
            }
        }, handler)
    }

    private fun processImage(image: android.media.Image): ByteArray? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            var bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Optional resizing to reduce bandwidth (max width = 720)
            val targetWidth = 720
            val targetHeight = image.height * targetWidth / image.width
            val resizedBitmap = if (bitmap.width > targetWidth) {
                Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            } else bitmap

            val croppedBitmap = Bitmap.createBitmap(resizedBitmap, 0, 0, image.width, image.height)

            val baos = ByteArrayOutputStream()
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 30, baos) // Lower quality for smaller size
            val frameBytes = baos.toByteArray()

            bitmap.recycle()
            if (resizedBitmap != bitmap) resizedBitmap.recycle()
            croppedBitmap.recycle()

            frameBytes
        } catch (e: Exception) {
            Log.e(TAG, "processImage failed: ${e.message}", e)
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Stopping ScreenCaptureService...")

        try {
            senderThread?.interrupt()
            senderThread?.join(1000)

            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            imageReader = null

            virtualDisplay?.release()
            virtualDisplay = null

            mediaProjection?.stop()
            mediaProjection = null

            handlerThread?.quitSafely()
            handlerThread?.join(1000)
            handlerThread = null
            handler = null

        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}", e)
        }

        Log.d(TAG, "ScreenCaptureService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
