package com.pixelated.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Settings screen: pixel size and the button that starts / stops the overlay. */
class MainActivity : Activity() {

    private lateinit var startButton: Button
    private lateinit var sizeLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(24)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            fitsSystemWindows = true
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.intro)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(8), 0, dp(24))
        })

        sizeLabel = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) }
        root.addView(sizeLabel)
        val sizes = Prefs.SIZES
        root.addView(SeekBar(this).apply {
            max = sizes.size - 1
            val current = Prefs.blockSize(this@MainActivity)
            progress = sizes.indexOfFirst { it >= current }.coerceAtLeast(0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    showSize(sizes[value])
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {
                    // Saved on release only: the running service rebuilds on every change.
                    Prefs.setBlockSize(this@MainActivity, sizes[bar.progress])
                }
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        showSize(Prefs.blockSize(this))

        root.addView(Switch(this).apply {
            text = getString(R.string.two_tone)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isChecked = Prefs.twoTone(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setTwoTone(this@MainActivity, on) }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        startButton = Button(this).apply { setOnClickListener { onStartStop() } }
        root.addView(startButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(24) })

        root.addView(TextView(this).apply {
            text = getString(R.string.how_to)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.START
            setPadding(0, dp(24), 0, 0)
            alpha = 0.75f
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        updateButton()
    }

    private fun showSize(size: Int) {
        sizeLabel.text = getString(R.string.pixel_size, size)
    }

    private fun updateButton(running: Boolean = PixelateService.isRunning) {
        startButton.text = getString(if (running) R.string.stop else R.string.start)
    }

    private fun onStartStop() {
        if (PixelateService.isRunning) {
            stopService(Intent(this, PixelateService::class.java))
            updateButton(false)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // The notification holds the Turn off / Stop actions; capture works without it.
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        requestCapture()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) requestCapture()
    }

    private fun requestCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Whole screen only: a single-app capture would leave other apps un-pixelated.
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, R.string.need_capture, Toast.LENGTH_LONG).show()
            return
        }
        startForegroundService(
            Intent(this, PixelateService::class.java)
                .setAction(PixelateService.ACTION_START)
                .putExtra(PixelateService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(PixelateService.EXTRA_RESULT_DATA, data)
        )
        updateButton(true)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_CAPTURE = 1
        private const val REQUEST_NOTIFICATIONS = 2
    }
}
