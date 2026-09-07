package com.tracer.overlay

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val button = Button(this).apply {
            text = "Start Overlay"
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    val serviceIntent = Intent(this@MainActivity, OverlayService::class.java)
                    startService(serviceIntent)
                } else {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
            }
        }

        setContentView(button)
    }
}
