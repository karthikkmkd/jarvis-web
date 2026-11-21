package com.jarvis.assistant

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import com.openwakeword.OpenWakeWord
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationService
import org.json.JSONObject

class WakeWordService : Service() {

    private var detector: OpenWakeWord? = null
    private var realtime: RealtimeVoiceClient? = null
    private var authService: AuthorizationService? = null
    private val handler = Handler(Looper.getMainLooper())
    private var sessionActive = false

    companion object {
        private const val CHANNEL_ID = "jarvis_wake_channel"
        private const val NOTIFICATION_ID = 101
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        authService = AuthorizationService(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, makeNotification("Starting Hey JARVIS..."))
        saveStatus("Loading Hey JARVIS model...")

        try {
            detector = OpenWakeWord.Builder(this)
                .setModel(OpenWakeWord.BuiltInModel.HEY_JARVIS)
                .setThreshold(0.5f)
                .build()

            startWakeDetection()
        } catch (e: Exception) {
            saveStatus("ERROR: ${e.message}")
            showWakeNotification("Wake detector failed")
        }

        return START_STICKY
    }

    private fun startWakeDetection() {
        if (sessionActive) return

        detector?.start(
            {
                if (!sessionActive) {
                    sessionActive = true
                    detector?.stop()
                    saveStatus("HEY JARVIS DETECTED! Connecting...")
                    showWakeNotification("Connecting to JARVIS Realtime...")
                    startRealtime()
                }
            },
            { status ->
                saveStatus(status)
                showWakeNotification(status)
            }
        )
    }

    private fun startRealtime() {
        val savedState = getSharedPreferences("jarvis_auth", MODE_PRIVATE)
            .getString("authState", null)

        if (savedState.isNullOrBlank()) {
            failSession("Please login to JARVIS first.")
            return
        }

        try {
            val authState = AuthState.jsonDeserialize(savedState)

            authState.performActionWithFreshTokens(authService!!) {
                    _, idToken, exception ->

                getSharedPreferences("jarvis_auth", MODE_PRIVATE)
                    .edit()
                    .putString("authState", authState.jsonSerializeString())
                    .apply()

                if (exception != null || idToken.isNullOrBlank()) {
                    failSession("Authentication failed. Please login again.")
                    return@performActionWithFreshTokens
                }

                realtime = RealtimeVoiceClient(
                    this,
                    { status ->
                        handler.post {
                            saveStatus(status)
                            showWakeNotification(status)
                        }
                    },
                    { event ->
                        handler.post {
                            saveStatus("Realtime: $event")
                        }
                    }
                )

                realtime?.fetchRealtimeToken(idToken) { ephemeralToken ->
                    handler.post {
                        if (ephemeralToken.isNullOrBlank()) {
                            stopRealtime()
                            handler.postDelayed({
                                sessionActive = false
                                startWakeDetection()
                            }, 4000)
                            return@post
                        }

                        try {
                            realtime?.initialize()
                            realtime?.createPeerConnection()
                            realtime?.createOffer { offer ->
                                handler.post {
                                    if (offer.isNullOrBlank()) {
                                        failSession("Failed to create SDP offer")
                                        return@post
                                    }

                                    realtime?.connectToOpenAI(ephemeralToken, offer) { connected ->
                                        handler.post {
                                            if (connected) {
                                                saveStatus("JARVIS Realtime connected. Speak now.")
                                                showWakeNotification("Realtime conversation active")
                                            } else {
                                                failSession("Realtime connection failed")
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            failSession("WebRTC setup error: ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                }
            }

        } catch (e: Exception) {
            failSession("Authentication error: ${e.message}")
        }
    }

    private fun failSession(message: String) {
        saveStatus(message)
        showWakeNotification(message)
        stopRealtime()
        handler.postDelayed({
            sessionActive = false
            startWakeDetection()
        }, 4000)
    }

    private fun stopRealtime() {
        realtime?.stop()
        realtime = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Wake Detection",
                NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun makeNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun saveStatus(value: String) {
        getSharedPreferences("jarvis_status", MODE_PRIVATE)
            .edit()
            .putString("status", value)
            .apply()
    }

    private fun showWakeNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, makeNotification(message))
    }

    override fun onDestroy() {
        sessionActive = false
        detector?.stop()
        detector?.release()
        detector = null
        stopRealtime()
        authService?.dispose()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
