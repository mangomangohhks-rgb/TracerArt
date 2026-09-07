package com.tracer.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.tracer.overlay.action.START"
        const val ACTION_TOGGLE_LOCK = "com.tracer.overlay.action.TOGGLE_LOCK"
        const val ACTION_STOP = "com.tracer.overlay.action.STOP"
        const val EXTRA_IMAGE_URI = "extra_image_uri"

        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "tracer_overlay_channel"
        private const val NOTIF_ID = 1001

        private const val MIN_OPACITY = 0.10f
        private const val MAX_OPACITY = 0.80f
        private const val DEFAULT_OPACITY = 0.30f

        private const val TAP_MOVE_SLOP_PX = 12f
    }

    private lateinit var windowManager: WindowManager

    // --- Window 1: the traced image + its control bar (activity_overlay.xml) ---
    private var overlayRootView: View? = null
    private var overlayImageView: ImageView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var originalBitmap: Bitmap? = null
    private var baseImageWidth = 0
    private var baseImageHeight = 0
    private var currentScale = 1f
    private var scaleGestureDetector: ScaleGestureDetector? = null

    // --- Window 2: tiny always-touchable floating unlock bubble ---
    private var unlockBubble: ImageView? = null
    private var unlockBubbleParams: WindowManager.LayoutParams? = null

    // Drag bookkeeping for setupDragAndScale()
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    // Separate drag bookkeeping for the unlock bubble
    private var bubbleInitialX = 0
    private var bubbleInitialY = 0
    private var bubbleInitialTouchX = 0f
    private var bubbleInitialTouchY = 0f
    private var bubbleIsDragging = false

    // --- Mode / tracing-optimization state ---
    private var isDrawMode = false   // false = MOVE (touchable), true = DRAW (locked + pass-through)
    private var isHighContrast = false
    private var isInverted = false
    private var currentOpacity = DEFAULT_OPACITY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_LOCK -> { toggleLock(); return START_STICKY }
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }

        startForeground(NOTIF_ID, buildNotification())

        val imageUriString = intent?.getStringExtra(EXTRA_IMAGE_URI)
        if (overlayRootView == null && imageUriString != null) {
            loadImage(Uri.parse(imageUriString))
            if (originalBitmap != null) {
                setupOverlayWindow()
                setupUnlockBubble()
            } else {
                Log.e(TAG, "Bitmap failed to decode; stopping overlay service.")
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        safeRemoveView(overlayRootView)
        safeRemoveView(unlockBubble)
        originalBitmap?.recycle()
        originalBitmap = null
    }

    private fun safeRemoveView(view: View?) {
        if (view == null) return
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            // Already detached — safe to ignore.
        }
    }

    private fun loadImage(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                originalBitmap = BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load reference image", e)
        }
    }

    private fun setupOverlayWindow() {
        val bitmap = originalBitmap ?: return
        val displayMetrics = resources.displayMetrics

        val root = LayoutInflater.from(this).inflate(R.layout.activity_overlay, null)
        overlayRootView = root

        overlayImageView = root.findViewById<ImageView>(R.id.overlayImageView).apply {
            setImageBitmap(bitmap)
        }

        val targetWidth = (displayMetrics.widthPixels * 0.7f).toInt()
        val aspect = bitmap.height.toFloat() / bitmap.width.toFloat()
        baseImageWidth = targetWidth
 baseImageHeight = (targetWidth * aspect).toInt()

        (overlayImageView?.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.width = baseImageWidth
            lp.height = baseImageHeight
            overlayImageView?.layoutParams = lp
        }

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            currentTouchFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (displayMetrics.widthPixels - baseImageWidth) / 2
            y = (displayMetrics.heightPixels - baseImageHeight) / 4
        }

        val btnLockDraw = root.findViewById<android.widget.Button>(R.id.btnLockDraw)
        val btnContrast = root.findViewById<android.widget.Button>(R.id.btnContrast)
        val btnInvert = root.findViewById<android.widget.Button>(R.id.btnInvert)
        val btnCloseOverlay = root.findViewById<android.widget.Button>(R.id.btnCloseOverlay)
        val seekOpacity = root.findViewById<SeekBar>(R.id.seekOpacity)

        seekOpacity.max = 80
        seekOpacity.min = 10
        seekOpacity.progress = (DEFAULT_OPACITY * 100).toInt()

        btnLockDraw.setOnClickListener { toggleLock() }
        btnContrast.setOnClickListener { isHighContrast = !isHighContrast; applyImageFilters() }
        btnInvert.setOnClickListener { isInverted = !isInverted; applyImageFilters() }
        btnCloseOverlay.setOnClickListener { stopSelf() }

        seekOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentOpacity = (progress.coerceIn(10, 80) / 100f).coerceIn(MIN_OPACITY, MAX_OPACITY)
                applyImageFilters()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        updateLockButtonLabel(btnLockDraw)
        setupDragAndScale(overlayImageView!!)

        windowManager.addView(overlayRootView, overlayParams)
        applyImageFilters()
    }

    private fun setupDragAndScale(target: View) {
        scaleGestureDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    currentScale = (currentScale * detector.scaleFactor).coerceIn(0.2f, 6f)
                    val params = overlayParams ?: return true
                    (overlayImageView?.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                        lp.width = (baseImageWidth * currentScale).toInt().coerceAtLeast(50)
                        lp.height = (baseImageHeight * currentScale).toInt().coerceAtLeast(50)
                        overlayImageView?.layoutParams = lp
                    }
                    overlayImageView?.requestLayout()
                    windowManager.updateViewLayout(overlayRootView, params)
                    return true
                }
            }
        )

        target.setOnTouchListener { _, event ->
            if (isDrawMode) return@setOnTouchListener false

            scaleGestureDetector?.onTouchEvent(event)

            val params = overlayParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1) {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(overlayRootView, params)
                    }
                }
            }
            true
        }
    }

    private fun currentTouchFlags(): Int {
        val base = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        return if (isDrawMode) {
            base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            base or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
    }

    private fun applyTouchFlags() {
        val params = overlayParams ?: return
        params.flags = currentTouchFlags()
        windowManager.updateViewLayout(overlayRootView, params)
    }

    private fun applyImageFilters() {
        val matrix = ColorMatrix()

        if (isHighContrast) {
            val saturation = ColorMatrix().apply { setSaturation(0f) }
            matrix.postConcat(saturation)

            val contrast = 3.2f
            val translate = (-0.5f * contrast + 0.5f) * 255f
            val contrastMatrix = ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            ))
            matrix.postConcat(contrastMatrix)
        }

        if (isInverted) {
            val invert = ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            ))
            matrix.postConcat(invert)
        }

        overlayImageView?.colorFilter = ColorMatrixColorFilter(matrix)
        overlayImageView?.alpha = currentOpacity
    }

    private fun updateLockButtonLabel(button: android.widget.Button?) {
        val label = button ?: overlayRootView?.findViewById(R.id.btnLockDraw) ?: return
        label.text = getString(if (isDrawMode) R.string.btn_state_draw else R.string.btn_mode_move)
    }

    // ---------------------------------------------------------------------
    // Window 2: floating unlock bubble
    // ---------------------------------------------------------------------

    private fun setupUnlockBubble() {
        val sizePx = (48 * resources.displayMetrics.density).toInt()
        val bubble = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_edit)
            setBackgroundColor(0xCC000000.toInt())
            contentDescription = getString(R.string.cd_unlock_bubble)
            setPadding(16, 16, 16, 16)
        }
        unlockBubble = bubble

        unlockBubbleParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - sizePx - 32
            y = 100
        }

        bubble.setOnTouchListener { _, event ->
            val params = unlockBubbleParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    bubbleInitialX = params.x
                    bubbleInitialY = params.y
                    bubbleInitialTouchX = event.rawX
                    bubbleInitialTouchY = event.rawY
                    bubbleIsDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - bubbleInitialTouchX
                    val dy = event.rawY - bubbleInitialTouchY
                    if (abs(dx) > TAP_MOVE_SLOP_PX || abs(dy) > TAP_MOVE_SLOP_PX) {
                        bubbleIsDragging = true
                    }
                    if (bubbleIsDragging) {
                        params.x = bubbleInitialX + dx.toInt()
                        params.y = bubbleInitialY + dy.toInt()
                        windowManager.updateViewLayout(unlockBubble, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!bubbleIsDragging) {
                        toggleLock()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(unlockBubble, unlockBubbleParams)
    }

    private fun toggleLock() {
        isDrawMode = !isDrawMode
        applyTouchFlags()
        updateLockButtonLabel(null)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIF_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tracer Overlay Control",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val toggleIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_TOGGLE_LOCK }
        val togglePending = PendingIntent.getService(this, 0, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val text = if (isDrawMode) getString(R.string.notif_text_draw) else getString(R.string.notif_text_move)
        val lockActionText = if (isDrawMode) "Unlock" else "Lock (Draw)"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TracerArt Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_manage, lockActionText, togglePending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .build()
    }
}
