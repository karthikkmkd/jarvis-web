package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {

    private val microphoneRequest = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = TextView(this).apply {
            text = "JARVIS\n\nPreparing Hey JARVIS wake detection..."
            textSize = 22f
            setPadding(32, 64, 32, 32)
        }
        setContentView(status)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startWakeService()
            status.text = "JARVIS\n\nWake service starting..."
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                microphoneRequest
            )
        }
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
        }
    }

    private fun startWakeService() {
        val intent = Intent(this, WakeWordService::class.java)
        startForegroundService(intent)
    }
}
