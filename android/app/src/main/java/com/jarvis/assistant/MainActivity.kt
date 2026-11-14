package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView

class MainActivity : Activity() {

    private val microphoneRequest = 100
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val refreshStatus = object : Runnable {
        override fun run() {
            if (::status.isInitialized) {
                val message = getSharedPreferences("jarvis_status", MODE_PRIVATE)
                    .getString("status", "Starting wake detector...")
                status.text = "JARVIS\n\n$message"
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "JARVIS\n\nChecking microphone permission..."
            textSize = 24f
            setPadding(32, 64, 32, 32)
        }
        setContentView(status)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startWakeService()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                microphoneRequest
            )
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshStatus)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshStatus)
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == microphoneRequest &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startWakeService()
        } else {
            status.text = "JARVIS\n\nMicrophone permission denied."
        }
    }

    private fun startWakeService() {
        status.text = "JARVIS\n\nStarting wake detector..."
        startForegroundService(Intent(this, WakeWordService::class.java))
    }
}
