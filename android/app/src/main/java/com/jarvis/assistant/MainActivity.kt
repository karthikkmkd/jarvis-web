package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import net.openid.appauth.*

class MainActivity : Activity() {

    private val microphoneRequest = 100
    private val authRequest = 200
    private lateinit var status: TextView
    private lateinit var authService: AuthorizationService

    private val clientId = "h78jmj9pr05oer6f2qao1gmph"
    private val redirectUri = Uri.parse("com.jarvis.assistant:/oauth2redirect")
    private val domain = "https://ap-southeast-2igtyzbczv.auth.ap-southeast-2.amazoncognito.com"

    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse("$domain/oauth2/authorize"),
        Uri.parse("$domain/oauth2/token")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        authService = AuthorizationService(this)

        status = TextView(this).apply {
            textSize = 20f
            text = "JARVIS\n\nReady"
        }

        val loginButton = Button(this).apply {
            text = "LOGIN TO JARVIS"
            setOnClickListener { login() }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
            addView(status)
            addView(loginButton)
        }

        setContentView(layout)

        val savedState = getSharedPreferences("jarvis_auth", MODE_PRIVATE)
            .getString("authState", null)

        if (savedState != null) {
            try {
                val authState = AuthState.jsonDeserialize(savedState)
                status.text = if (authState.isAuthorized) {
                    "JARVIS\n\nLogged in"
                } else {
                    "JARVIS\n\nPlease login"
                }
            } catch (_: Exception) {
                status.text = "JARVIS\n\nPlease login"
            }
        }

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

    private fun login() {
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            clientId,
            ResponseTypeValues.CODE,
            redirectUri
        )
            .setScope("openid email")
            .build()

        status.text = "JARVIS\n\nOpening secure login..."

        startActivityForResult(
            authService.getAuthorizationRequestIntent(request),
            authRequest
        )
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == authRequest) {
            if (data == null) {
                status.text = "JARVIS\n\nLogin cancelled."
                return
            }

            val response = AuthorizationResponse.fromIntent(data)
            val exception = AuthorizationException.fromIntent(data)

            if (response != null) {
                val authState = AuthState(serviceConfig)
                authState.update(response, exception)

                authService.performTokenRequest(
                    response.createTokenExchangeRequest()
                ) { tokenResponse, tokenException ->

                    authState.update(tokenResponse, tokenException)

                    getSharedPreferences("jarvis_auth", MODE_PRIVATE)
                        .edit()
                        .putString("authState", authState.jsonSerializeString())
                        .apply()

                    runOnUiThread {
                        status.text = if (authState.isAuthorized) {
                            "JARVIS\n\nLogin successful!"
                        } else {
                            "JARVIS\n\nLogin failed. Please try again."
                        }
                    }
                }
            } else {
                status.text = "JARVIS\n\nLogin cancelled or failed."
            }
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
        startForegroundService(Intent(this, WakeWordService::class.java))
    }

    override fun onDestroy() {
        authService.dispose()
        super.onDestroy()
    }
}
