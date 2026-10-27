package com.pixelated.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.max

/**
 * Foreground service that owns the screen capture, the pixelation overlay and the floating
 * on/off switch. Started by [MainActivity] once the user has granted screen capture.
 */
class PixelateService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        const val ACTION_START = "com.pixelated.app.START"
        const val ACTION_TOGGLE = "com.pixelated.app.TOGGLE"
        const val ACTION_STOP = "com.pixelated.app.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        /**
         * Overlay window alpha. Since Android 12 touches are only passed through another app's
         * overlay when it is at most 80% opaque; older versions allow a stronger effect.
         */
        val OVERLAY_ALPHA = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.8f else 0.9f

        /**
         * Captures at 1/2 resolution (plenty for block averages, 4x less work), except for
         * 1 px blocks, which need every screen pixel.
         */
        private fun captureDivisor(block: Int) = if (block >= 2) 2 else 1
        private const val CHANNEL = "pixelate"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "Pixelated"

        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var uiContext: Context
    private lateinit var windowManager: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val captureThread = HandlerThread("pixelate-capture").apply { start() }
    private val capture = Handler(captureThread.looper)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private var overlay: PixelOverlayView? = null
    private var floatingSwitch: FloatingSwitch? = null

    // Written on the main thread, read on the capture thread.
    @Volatile private var engine: PixelEngine? = null
    @Volatile private var switchBox: Box? = null
    @Volatile private var enabled = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // A window context gives correct window metrics/insets and is the right context for overlays.
        uiContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val display = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
            createDisplayContext(display).createWindowContext(overlayType(), null)
        } else {
            this
        }
        windowManager = uiContext.getSystemService(WINDOW_SERVICE) as WindowManager
        Prefs.get(this).registerOnSharedPreferenceChangeListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_TOGGLE -> setEnabled(!enabled)
            ACTION_START -> if (projection == null) start(intent)
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        // Must be in the foreground (with the mediaProjection type) before using the token.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = data?.let { manager.getMediaProjection(code, it) }
        if (mp == null) {
            Log.w(TAG, "Screen capture was not granted")
            stopSelf()
            return
        }
        projection = mp
        // Called when the user stops sharing from the status bar / lock screen.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, main)

        isRunning = true
        addOverlay() // first, so the floating switch (added next) sits above it
        addFloatingSwitch()
        rebuild()
    }

    // ------------------------------------------------------------------ capture

    /** (Re)creates the capture surface and engine for the current screen size and block size. */
    private fun rebuild() {
        val mp = projection ?: return
        val (screenW, screenH, content) = measureScreen()
        val block = Prefs.blockSize(this)
        val divisor = captureDivisor(block)
        val capW = max(1, screenW / divisor)
        val capH = max(1, screenH / divisor)
        val dpi = max(1, resources.displayMetrics.densityDpi / divisor)

        val newReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        newReader.setOnImageAvailableListener({ onImage(it) }, capture)
        val vd = virtualDisplay
        if (vd == null) {
            virtualDisplay = mp.createVirtualDisplay(
                "pixelated", capW, capH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface, null, null,
            )
        } else {
            vd.resize(capW, capH, dpi)
            vd.surface = newReader.surface
        }
        val oldReader = reader
        reader = newReader
        capture.post { oldReader?.close() }

        engine = PixelEngine(screenW, screenH, content, block, capW, capH, OVERLAY_ALPHA, Prefs.twoTone(this))
        overlay?.let { view ->
            val params = view.layoutParams
            if (params.width != screenW || params.height != screenH) {
                params.width = screenW
                params.height = screenH
                windowManager.updateViewLayout(view, params)
            }
        }
        updateSwitchBox()
        restartFrames()
    }

    /** Puts a fresh "raw" frame on screen; the engine bootstraps from the capture of it. */
    private fun restartFrames() {
        val e = engine ?: return
        if (!enabled) return
        overlay?.visibility = View.VISIBLE
        capture.post {
            val frame = e.reset()
            main.post { if (e === engine && enabled) overlay?.show(e, frame) }
        }
    }

    /** Runs on the capture thread for every new screen frame. */
    private fun onImage(r: ImageReader) {
        val image = try {
            r.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        try {
            val e = engine
            if (!enabled || e == null || image.width != e.capW || image.height != e.capH) return
            val plane = image.planes[0]
            val frame = e.process(plane.buffer, plane.rowStride, plane.pixelStride, switchBox) ?: return
            main.post { if (e === engine && enabled) overlay?.show(e, frame) }
        } finally {
            image.close()
        }
    }

    private data class ScreenSize(val width: Int, val height: Int, val content: Box)

    private fun measureScreen(): ScreenSize {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val b = metrics.bounds
            val i = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return ScreenSize(b.width(), b.height(), Box(i.left, i.top, b.width() - i.right, b.height() - i.bottom))
        }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        val status = dimen("status_bar_height")
        val landscape = dm.widthPixels > dm.heightPixels
        val nav = dimen(if (landscape) "navigation_bar_width" else "navigation_bar_height")
        val content = if (landscape) Box(0, status, dm.widthPixels - nav, dm.heightPixels)
        else Box(0, status, dm.widthPixels, dm.heightPixels - nav)
        return ScreenSize(dm.widthPixels, dm.heightPixels, content)
    }

    @Suppress("DiscouragedApi", "InternalInsetResource")
    private fun dimen(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id != 0) resources.getDimensionPixelSize(id) else 0
    }

    // ------------------------------------------------------------------ windows

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    /** Full-screen, click-through window for the pixels. Stays attached; switching off just hides it. */
    private fun addOverlay() {
        val (w, h) = measureScreen()
        val params = WindowManager.LayoutParams(
            w, h,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = OVERLAY_ALPHA
            title = "Pixelated overlay"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val view = PixelOverlayView(uiContext)
        view.visibility = if (enabled) View.VISIBLE else View.GONE
        windowManager.addView(view, params)
        overlay = view
    }

    private fun hideOverlay() {
        overlay?.let {
            it.clear()
            it.visibility = View.GONE
        }
    }

    private fun addFloatingSwitch() {
        val density = resources.displayMetrics.density
        val (screenW, screenH) = measureScreen()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - (80 * density).toInt()
            y = screenH / 3
            title = "Pixelated switch"
        }
        val view = FloatingSwitch(uiContext, windowManager, params, onToggle = ::setEnabled, onMoved = ::updateSwitchBox)
        view.isOn = enabled
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSwitchBox() }
        windowManager.addView(view, params)
        floatingSwitch = view
    }

    /** Tells the engine where the switch is, so its pixels are left out of the captures. */
    private fun updateSwitchBox() {
        val view = floatingSwitch ?: return
        if (!view.isAttachedToWindow) return
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        switchBox = Box(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
    }

    private fun setEnabled(on: Boolean) {
        enabled = on
        floatingSwitch?.isOn = on
        if (on) restartFrames() else hideOverlay()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification())
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation / resolution change: let the window metrics settle, then rebuild.
        main.postDelayed({ rebuild() }, 300)
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key == Prefs.KEY_BLOCK || key == Prefs.KEY_TWO_TONE) rebuild()
    }

    override fun onDestroy() {
        isRunning = false
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(this)
        main.removeCallbacksAndMessages(null)
        overlay?.let { if (it.isAttachedToWindow) windowManager.removeViewImmediate(it) }
        floatingSwitch?.let { if (it.isAttachedToWindow) windowManager.removeViewImmediate(it) }
        virtualDisplay?.release()
        projection?.stop()
        val r = reader
        capture.post { r?.close() }
        captureThread.quitSafely()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val toggle = PendingIntent.getService(this, 1, Intent(this, PixelateService::class.java).setAction(ACTION_TOGGLE), flags)
        val stop = PendingIntent.getService(this, 2, Intent(this, PixelateService::class.java).setAction(ACTION_STOP), flags)
        val icon = Icon.createWithResource(this, R.drawable.ic_pixel)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_pixel)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (enabled) R.string.status_on else R.string.status_off))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(icon, getString(if (enabled) R.string.turn_off else R.string.turn_on), toggle).build())
            .addAction(Notification.Action.Builder(icon, getString(R.string.stop), stop).build())
            .build()
    }
}
