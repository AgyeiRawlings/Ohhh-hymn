package com.example.socketclient

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ScreenCaptureService : Service() {

    companion object {
        const val SERVER_IP = "192.168.43.27"
        const val SERVER_PORT = 4444
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")

        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("data")
        }

        if (resultData != null) {
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, resultData)

            if (mediaProjection != null) {
                startForeground(NOTIFICATION_ID, createNotification())
                startHandlerThread()
                startSenderThread()
                startScreenCapture()
            } else {
                Log.e(TAG, "Failed to get MediaProjection")
                stopSelf()
            }
        } else {
            Log.w(TAG, "Result data is null or missing")
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
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Screen Capture")
        .setContentText("Capturing screen...")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .build()

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
                            Log.d(TAG, "Connected to server $SERVER_IP:$SERVER_PORT")
                        } catch (e: Exception) {
                            Log.e(TAG, "Connect failed: ${e.message}")
                            socket?.close()
                            socket = null
                            out = null
                            Thread.sleep(2000)
                            continue
                        }
                    }

                    val frame = frameQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue

                    try {
                        val sizeBytes = frame.size.toString().toByteArray(Charsets.US_ASCII)
                        out?.write(sizeBytes)
                        out?.write('\n'.code)
                        out?.write(frame)
                        out?.flush()
                        Log.d(TAG, "Frame sent: ${frame.size} bytes")
                    } catch (e: Exception) {
                        Log.e(TAG, "Send failed: ${e.message}")
                        socket?.close()
                        socket = null
                        out = null
                        frameQueue.offer(frame)
                        Thread.sleep(1000)
                    }
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    Log.e(TAG, "Sender thread error: ${e.message}")
                }
            }

            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
        senderThread!!.start()
    }

    private fun startScreenCapture() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val screenDensity = metrics.densityDpi

        Log.d(TAG, "Screen size: ${width}x$height, density: $screenDensity")

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width,
            height,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            if (now - lastFrameTime < FRAME_INTERVAL_MS) {
                val imgToDrop = reader.acquireLatestImage()
                imgToDrop?.close()
                return@setOnImageAvailableListener
            }
            lastFrameTime = now

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            try {
                val plane = image.planes[0]
                val buffer: ByteBuffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmapWidth = if (pixelStride != 0) width + rowPadding / pixelStride else width

                val bmp: Bitmap? = try {
                    buffer.rewind()
                    val tmp = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
                    tmp.copyPixelsFromBuffer(buffer)
                    Bitmap.createBitmap(tmp, 0, 0, width, height).also { tmp.recycle() }
                } catch (e: Exception) {
                    Log.e(TAG, "Bitmap creation failed: ${e.message}")
                    null
                }

                if (bmp != null) {
                    val baos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 50, baos)
                    val bytes = baos.toByteArray()
                    baos.close()

                    if (!frameQueue.offer(bytes)) {
                        Log.w(TAG, "Frame queue full, dropping frame")
                    }
                    bmp.recycle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image: ${e.message}")
            } finally {
                image.close()
            }

        }, handler)
    }

    override fun onDestroy() {
        try {
            handlerThread?.quitSafely()
            handlerThread?.join(500)
        } catch (_: Exception) {
        }
        try {
            senderThread?.interrupt()
            senderThread?.join(500)
        } catch (_: Exception) {
        }
        virtualDisplay?.release()
        imageReader?.close()
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
        frameQueue.clear()
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}        const val NOTIFICATION_ID = 1
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")

        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("data")
        }

        if (resultData != null) {
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, resultData)

            if (mediaProjection != null) {
                startForeground(NOTIFICATION_ID, createNotification())
                startHandlerThread()
                startSenderThread()
                startScreenCapture()
            } else {
                Log.e(TAG, "Failed to get MediaProjection")
                stopSelf()
            }
        } else {
            Log.w(TAG, "Result data is null or missing")
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
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Screen Capture")
        .setContentText("Capturing screen...")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .build()

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
                            Log.d(TAG, "Connected to server $SERVER_IP:$SERVER_PORT")
                        } catch (e: Exception) {
                            Log.e(TAG, "Connect failed: ${e.message}")
                            socket?.close()
                            socket = null
                            out = null
                            Thread.sleep(2000)
                            continue
                        }
                    }

                    val frame = frameQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue

                    try {
                        val sizeBytes = frame.size.toString().toByteArray(Charsets.US_ASCII)
                        out?.write(sizeBytes)
                        out?.write('\n'.code)
                        out?.write(frame)
                        out?.flush()
                        Log.d(TAG, "Frame sent: ${frame.size} bytes")
                    } catch (e: Exception) {
                        Log.e(TAG, "Send failed: ${e.message}")
                        socket?.close()
                        socket = null
                        out = null
                        frameQueue.offer(frame)
                        Thread.sleep(1000)
                    }
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    Log.e(TAG, "Sender thread error: ${e.message}")
                }
            }

            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
        senderThread!!.start()
    }

    private fun startScreenCapture() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val screenDensity = metrics.densityDpi

        Log.d(TAG, "Screen size: ${width}x$height, density: $screenDensity")

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width,
            height,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            if (now - lastFrameTime < FRAME_INTERVAL_MS) {
                val imgToDrop = reader.acquireLatestImage()
                imgToDrop?.close()
                return@setOnImageAvailableListener
            }
            lastFrameTime = now

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            try {
                val plane = image.planes[0]
                val buffer: ByteBuffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmapWidth = if (pixelStride != 0) width + rowPadding / pixelStride else width

                val bmp: Bitmap? = try {
                    buffer.rewind()
                    val tmp = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
                    tmp.copyPixelsFromBuffer(buffer)
                    Bitmap.createBitmap(tmp, 0, 0, width, height).also { tmp.recycle() }
                } catch (e: Exception) {
                    Log.e(TAG, "Bitmap creation failed: ${e.message}")
                    null
                }

                if (bmp != null) {
                    val baos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 50, baos)
                    val bytes = baos.toByteArray()
                    baos.close()

                    if (!frameQueue.offer(bytes)) {
                        Log.w(TAG, "Frame queue full, dropping frame")
                    }
                    bmp.recycle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image: ${e.message}")
            } finally {
                image.close()
            }

        }, handler)
    }

    override fun onDestroy() {
        try {
            handlerThread?.quitSafely()
            handlerThread?.join(500)
        } catch (_: Exception) {
        }
        try {
            senderThread?.interrupt()
            senderThread?.join(500)
        } catch (_: Exception) {
        }
        virtualDisplay?.release()
        imageReader?.close()
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
        frameQueue.clear()
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}    private lateinit var frameQueue: BlockingQueue<Bitmap>
    private lateinit var senderThread: Thread
    private var socket: Socket? = null

    override fun onCreate() {
        super.onCreate()
        frameQueue = ArrayBlockingQueue(10)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.e(TAG, "Intent is null, cannot start service")
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        val data = intent.getParcelableExtra<Intent>("data")!!

        val mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

        startForegroundService()
        startScreenCapture()

        return START_STICKY
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Capture")
            .setContentText("Capturing screen...")
            .setSmallIcon(R.drawable.ic_notification) // make sure you have this drawable
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startScreenCapture() {
        imageReader = ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenCapture", 1280, 720,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            image?.let {
                processFrame(it)
                it.close()
            } ?: run {
                Log.e(TAG, "Failed to acquire image")
            }
        }, Looper.myLooper())
    }

    private fun processFrame(image: Image) {
        val bitmap = imageToBitmap(image)
        try {
            sendFrameToSocket(bitmap)
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun sendFrameToSocket(frame: Bitmap) {
        try {
            if (socket == null || socket!!.isClosed) {
                socket = Socket(SERVER_IP, SERVER_PORT)
            }
            val byteArray = compressFrame(frame)
            socket?.getOutputStream()?.let { outputStream ->
                outputStream.write(ByteBuffer.allocate(4).putInt(byteArray.size).array())
                outputStream.write(byteArray)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending frame: ${e.message}")
            socket?.close()
            socket = null
        }
    }

    private fun compressFrame(frame: Bitmap): ByteArray {
        val byteArrayOutputStream = ByteArrayOutputStream()
        frame.compress(Bitmap.CompressFormat.JPEG, 85, byteArrayOutputStream)
        return byteArrayOutputStream.toByteArray()
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            virtualDisplay.release()
            imageReader.close()
            mediaProjection.stop()
            socket?.close()
        } catch (_: Exception) {
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}    private lateinit var frameQueue: BlockingQueue<Bitmap>
    private lateinit var senderThread: Thread
    private var socket: Socket? = null

    override fun onCreate() {
        super.onCreate()
        frameQueue = ArrayBlockingQueue(10)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.e(TAG, "Intent is null, cannot start service")
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        val data = intent.getParcelableExtra<Intent>("data")!!

        val mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

        startForegroundService()

        startScreenCapture()

        return super.onStartCommand(intent, flags, startId)
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Capture")
            .setContentText("Capturing screen...")
            .setSmallIcon(R.drawable.ic_notification)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startScreenCapture() {
        imageReader = ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenCapture", 1280, 720,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            image?.let {
                processFrame(it)
                it.close()
            } ?: run {
                Log.e(TAG, "Failed to acquire image")
            }
        }, Looper.myLooper())

        startSenderThread()
    }

    private fun processFrame(image: Image) {
        val bitmap = imageToBitmap(image)

        try {
            sendFrameToSocket(bitmap)
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle() // Free up memory
            }
        }
    }

    private fun sendFrameToSocket(frame: Bitmap) {
        val byteArray = compressFrame(frame)
        socket?.getOutputStream()?.let { outputStream ->
            outputStream.write(ByteBuffer.allocate(4).putInt(byteArray.size).array()) // Write frame size
            outputStream.write(byteArray) // Write frame data
        }
    }

    private fun compressFrame(frame: Bitmap): ByteArray {
        val byteArrayOutputStream = ByteArrayOutputStream()
        frame.compress(Bitmap.CompressFormat.JPEG, 85, byteArrayOutputStream)
        return byteArrayOutputStream.toByteArray()
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }

    private fun startSenderThread() {
        senderThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    socket = Socket(SERVER_IP, SERVER_PORT)
                    val outputStream
    // Small queue to decouple image capture from network sending
    private val frameQueue = LinkedBlockingQueue<ByteArray>(8)
    private var senderThread: Thread? = null

    // Server info (same as you gave)
    private val SERVER_IP = "192.168.43.27"
    private val SERVER_PORT = 4444

    // Throttle: send at most one frame every FRAME_INTERVAL_MS (default 200ms -> 5 FPS)
    private val FRAME_INTERVAL_MS = 200L
    private var lastFrameTime = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")

        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("mediaProjectionData", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("mediaProjectionData")
        }

        if (resultData != null) {
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, resultData)

            if (mediaProjection != null) {
                startHandlerThread()      // for image processing
                startSenderThread()       // for network sending and reconnects
                startScreenCapture()      // start capturing frames
            } else {
                Log.e(TAG, "Failed to get MediaProjection")
            }
        } else {
            Log.w(TAG, "Result data is null or missing")
        }

        return START_STICKY
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
                    // ensure connected
                    if (socket == null || socket.isClosed || !socket.isConnected) {
                        try {
                            socket = Socket()
                            socket.connect(InetSocketAddress(SERVER_IP, SERVER_PORT), 5000)
                            out = socket.getOutputStream()
                            Log.d(TAG, "Connected to server $SERVER_IP:$SERVER_PORT")
                        } catch (e: Exception) {
                            Log.e(TAG, "Connect failed: ${e.message}")
                            socket?.close()
                            socket = null
                            out = null
                            Thread.sleep(2000) // retry delay
                            continue
                        }
                    }

                    // get frame (wait up to 500ms)
                    val frame = frameQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue

                    try {
                        // Protocol: send ASCII size + newline, then raw JPEG bytes
                        val sizeBytes = frame.size.toString().toByteArray(Charsets.US_ASCII)
                        out?.write(sizeBytes)
                        out?.write('\n'.code)
                        out?.write(frame)
                        out?.flush()
                        Log.d(TAG, "Frame sent: ${frame.size} bytes")
                    } catch (e: Exception) {
                        Log.e(TAG, "Send failed: ${e.message}")
                        // close and attempt reconnect
                        socket?.close()
                        socket = null
                        out = null
                        // try to requeue the frame (if queue not full)
                        frameQueue.offer(frame)
                        Thread.sleep(1000)
                    }
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    Log.e(TAG, "Sender thread unexpected error: ${e.message}")
                }
            }

            try { socket?.close() } catch (_: Exception) {}
        }
        senderThread!!.start()
    }

    private fun startScreenCapture() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val screenDensity = metrics.densityDpi

        Log.d(TAG, "Screen size: ${width}x$height, density: $screenDensity")

        // create ImageReader (RGBA_8888)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width,
            height,
            screenDensity,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        // Use the handler associated with handlerThread so processing is off the UI thread.
        imageReader?.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            if (now - lastFrameTime < FRAME_INTERVAL_MS) {
                // throttle: drop the frame if it's too soon
                val imgToDrop = reader.acquireLatestImage()
                imgToDrop?.close()
                return@setOnImageAvailableListener
            }
            lastFrameTime = now

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            // Processing happens on the handler thread because we supplied 'handler'
            try {
                // Many devices provide a single plane for RGBA_8888
                val plane = image.planes[0]
                val buffer: ByteBuffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                // Create a temporary bitmap that accounts for row padding (common pattern).
                // We will crop it back to the real width/height afterwards.
                val bitmapWidth = if (pixelStride != 0) width + rowPadding / pixelStride else width

                val bmp: Bitmap? = try {
                    buffer.rewind()
                    val tmp = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
                    tmp.copyPixelsFromBuffer(buffer)           // may throw on some devices - caught below
                    // crop to the real width/height
                    Bitmap.createBitmap(tmp, 0, 0, width, height).also { tmp.recycle() }
                } catch (e: Exception) {
                    Log.e(TAG, "Bitmap creation failed: ${e.message}")
                    null
                }

                if (bmp != null) {
                    // Compress to JPEG on the background thread
                    val baos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 50, baos) // 50% quality
                    val bytes = baos.toByteArray()
                    baos.close()

                    // Offer to the queue (non-blocking). If the queue is full we drop the frame.
                    if (!frameQueue.offer(bytes)) {
                        Log.w(TAG, "Frame queue full, dropping frame")
                    }
                    bmp.recycle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image: ${e.message}")
            } finally {
                image.close()
            }

        }, handler)
    }

    override fun onDestroy() {
        try {
            handlerThread?.quitSafely()
            handlerThread?.join(500)
        } catch (_: Exception) {}
        try {
            senderThread?.interrupt()
            senderThread?.join(500)
        } catch (_: Exception) {}
        virtualDisplay?.release()
        imageReader?.close()
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null
        frameQueue.clear()
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ScreenCaptureService"
    }
}
