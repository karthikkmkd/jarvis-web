package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.openwakeword.OpenWakeWord

class WakeWordService : Service() {

    private var detector: OpenWakeWord? = null

    companion object {
        private const val CHANNEL_ID = "jarvis_wake_channel"
        private const val NOTIFICATION_ID = 101
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, makeNotification("Listening for Hey JARVIS"))

        try {
            detector = OpenWakeWord.Builder(this)
                .setModel(OpenWakeWord.BuiltInModel.HEY_JARVIS)
                .setThreshold(0.5f)
                .build()

            detector?.start { score ->
                showWakeNotification("Hey JARVIS detected! Score: %.2f".format(score))
            }
        } catch (e: Exception) {
            showWakeNotification("Wake detector error: ${e.message}")
        }

        return START_STICKY
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

    private fun showWakeNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, makeNotification(message))
    }

    override fun onDestroy() {
        detector?.stop()
        detector?.release()
        detector = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
