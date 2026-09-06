package com.tracer.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import android.widget.SeekBar
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class OverlayService : Service() {
    companion object {
        const val ACTION_START = "com.tracer.overlay.action.START"
        const val ACTION_TOGGLE_MODE = "com.tracer.overlay.action.TOGGLE_MODE"
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
    private var imageView: ImageView? = null
    private var imageParams: WindowManager.LayoutParams? = null
    private var originalBitmap: Bitmap? = null
    private var baseImageWidth = 0
    private var baseImageHeight = 0
    private var currentScale = 1f
    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var controlRoot: View? = null
    private var controlParams: WindowManager.LayoutParams? = null
    private var panelExpanded = false
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragStartTouchX = 0f
    private var dragStartTouchY = 0f
    private var isDragging = false
    private var isTraceMode = false
    private var isLocked = false
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
            ACTION_TOGGLE_MODE -> { toggleMode(); return START_STICKY }
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        startForeground(NOTIF_ID, buildNotification())
        val imageUriString = intent?.getStringExtra(EXTRA_IMAGE_URI)
        if (imageView == null && imageUriString != null) {
            loadImage(Uri.parse(imageUriString))
            if (originalBitmap != null) {
                setupImageOverlay()
                setupControlBubble()
            } else {
                Log.e(TAG, "Bitmap failed to decode; stopping overlay service.")
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isLocked) return
    }

    override fun onDestroy() {
        super.onDestroy()
        safeRemoveView(imageView)
        safeRemoveView(controlRoot)
        originalBitmap?.recycle()
        originalBitmap = null
    }

    private fun safeRemoveView(view: View?) {
        if (view == null) return
        try { windowManager.removeView(view) } catch (e: IllegalArgumentException) { }
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

    private fun setupImageOverlay() {
        val bitmap = originalBitmap ?: return
        val displayMetrics = resources.displayMetrics
        val targetWidth = (displayMetrics.widthPixels * 0.7f).toInt()
        val aspect = bitmap.height.toFloat() / bitmap.width.toFloat()
        baseImageWidth = targetWidth
        baseImageHeight = (targetWidth * aspect).toInt()

        val params = WindowManager.LayoutParams(
            baseImageWidth,
            baseImageHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (displayMetrics.widthPixels - baseImageWidth) / 2
            y = (displayMetrics.heightPixels - baseImageHeight) / 3
        }
        imageParams = params

        val iv = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_XY
            alpha = currentOpacity
        }
        imageView = iv

        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (isLocked || isTraceMode) return false
                currentScale *= detector.scaleFactor
                currentScale = currentScale.coerceIn(0.2f, 5.0f)
                val p = imageParams ?: return false
                p.width = (baseImageWidth * currentScale).toInt()
                p.height = (baseImageHeight * currentScale).toInt()
                try {
                    windowManager.updateViewLayout(imageView, p)
                } catch (_: Exception) {}
                return true
            }
        })

        setupImageTouchListener()
        windowManager.addView(iv, params)
        applyFilters()
    }

    private fun setupImageTouchListener() {
        val iv = imageView ?: return
        iv.setOnTouchListener { _, event ->
            if (isTraceMode || isLocked) return@setOnTouchListener false
            scaleGestureDetector?.onTouchEvent(event)

            val p = imageParams ?: return@setOnTouchListener false
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = p.x
                    dragStartY = p.y
                    dragStartTouchX = event.rawX
                    dragStartTouchY = event.rawY
                    isDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1) {
                        val dx = event.rawX - dragStartTouchX
                        val dy = event.rawY - dragStartTouchY
                        if (abs(dx) > TAP_MOVE_SLOP_PX || abs(dy) > TAP_MOVE_SLOP_PX) {
                            isDragging = true
                        }
                        if (isDragging) {
                            p.x = (dragStartX + dx).toInt()
                            p.y = (dragStartY + dy).toInt()
                            try {
                                windowManager.updateViewLayout(iv, p)
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
            true
        }
    }

    private fun setupControlBubble() {
        val inflater = LayoutInflater.from(this)
        val root = inflater.inflate(R.layout.overlay_control_panel, null)
        controlRoot = root

        val cParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 200
        }
        controlParams = cParams

        val bubble = root.findViewById<View>(R.id.bubbleIcon)
        val panel = root.findViewById<View>(R.id.panelContainer)
        val btnMode = root.findViewById<android.widget.Button>(R.id.btnToggleMode)
        val btnLock = root.findViewById<android.widget.Button>(R.id.btnToggleLock)
        val btnContrast = root.findViewById<android.widget.Button>(R.id.btnToggleContrast)
        val btnInvert = root.findViewById<android.widget.Button>(R.id.btnToggleInvert)
        val seekBarOpacity = root.findViewById<SeekBar>(R.id.seekBarOpacity)
        val btnClose = root.findViewById<android.widget.Button>(R.id.btnCloseService)

        bubble.setOnClickListener {
            panelExpanded = !panelExpanded
            panel.visibility = if (panelExpanded) View.VISIBLE else View.GONE
        }

        var bStartX = 0
        var bStartY = 0
        var bTouchX = 0f
        var bTouchY = 0f
        var bIsDragging = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    bStartX = cParams.x
                    bStartY = cParams.y
                    bTouchX = event.rawX
                    bTouchY = event.rawY
                    bIsDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - bTouchX
                    val dy = event.rawY - bTouchY
                    if (abs(dx) > TAP_MOVE_SLOP_PX || abs(dy) > TAP_MOVE_SLOP_PX) {
                        bIsDragging = true
                    }
                    if (bIsDragging) {
                        cParams.x = (bStartX + dx).toInt()
                        cParams.y = (bStartY + dy).toInt()
                        try {
                            windowManager.updateViewLayout(root, cParams)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!bIsDragging) {
                        bubble.performClick()
                    }
                    true
                }
                else -> false
            }
        }

        btnMode.setOnClickListener { toggleMode() }
        btnLock.setOnClickListener {
            isLocked = !isLocked
            btnLock.text = if (isLocked) "Locked" else "Unlocked"
        }
        btnContrast.setOnClickListener {
            isHighContrast = !isHighContrast
            applyFilters()
        }
        btnInvert.setOnClickListener {
            isInverted = !isInverted
            applyFilters()
        }

        seekBarOpacity.max = ((MAX_OPACITY - MIN_OPACITY) * 100).toInt()
        seekBarOpacity.progress = ((DEFAULT_OPACITY - MIN_OPACITY) * 100).toInt()
        seekBarOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentOpacity = MIN_OPACITY + (progress / 100f)
                imageView?.alpha = currentOpacity
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnClose.setOnClickListener { stopSelf() }

        windowManager.addView(root, cParams)
    }

    private fun toggleMode() {
        isTraceMode = !isTraceMode
        val iv = imageView ?: return
        val p = imageParams ?: return

        if (isTraceMode) {
            p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        try {
            windowManager.updateViewLayout(iv, p)
        } catch (_: Exception) {}

        val btnMode = controlRoot?.findViewById<android.widget.Button>(R.id.btnToggleMode)
        btnMode?.text = if (isTraceMode) "TRACE MODE (Ghost)" else "MOVE MODE"
        updateNotification()
    }

    private fun applyFilters() {
        val iv = imageView ?: return
        if (!isHighContrast && !isInverted) {
            iv.colorFilter = null
            return
        }

        val cm = ColorMatrix()
        if (isHighContrast) {
            val contrast = 2.0f
            val translate = (-0.5f * contrast + 0.5f) * 255f
            cm.set(floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            ))
        }

        if (isInverted) {
            val invertMatrix = ColorMatrix(floatArrayOf(
                -1f,  0f,  0f, 0f, 255f,
                 0f, -1f,  0f, 0f, 255f,
                 0f,  0f, -1f, 0f, 255f,
                 0f,  0f,  0f, 1f,   0f
            ))
            cm.postConcat(invertMatrix)
        }

        iv.colorFilter = ColorMatrixColorFilter(cm)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tracer Overlay Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val toggleIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_TOGGLE_MODE
        }
        val pendingToggle = PendingIntent.getService(
            this, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val modeLabel = if (isTraceMode) "Switch to Move Mode" else "Switch to Trace Mode"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tracer Overlay Active")
            .setContentText("Current: " + if (isTraceMode) "TRACE MODE (Passthrough)" else "MOVE MODE")
            .setSmallIcon(R.drawable.ic_stat_trace)
            .setOngoing(true)
            .addAction(0, modeLabel, pendingToggle)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, buildNotification())
    }
}
