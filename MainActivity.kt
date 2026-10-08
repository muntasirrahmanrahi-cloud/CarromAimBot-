package com.example.carromaimbot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    private val captureReq = 42
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        root.addView(TextView(this).apply {
            text = "Carrom Aim Bot\nLive trajectory overlay"
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text = "Detects pucks/striker from the live screen and draws the best direct or 1-cushion shot. It does not auto-click."
            textSize = 15f; setPadding(0, 20, 0, 20)
        })
        root.addView(Button(this).apply {
            text = "1. Allow display over other apps"
            setOnClickListener { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
        })
        root.addView(Button(this).apply {
            text = "2. Start live aim"
            setOnClickListener {
                val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(pm.createScreenCaptureIntent(), captureReq)
            }
        })
        root.addView(Button(this).apply {
            text = "Stop aim overlay"
            setOnClickListener { stopService(Intent(this@MainActivity, CaptureService::class.java)) }
        })
        setContentView(root)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureReq && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .putExtra("resultCode", resultCode)
                .putExtra("data", data)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
    }
}
