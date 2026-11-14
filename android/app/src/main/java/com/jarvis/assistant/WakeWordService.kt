package com.jarvis.assistant

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.openwakeword.OpenWakeWord

class WakeWordService : Service() {

    private var detector: OpenWakeWord? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isRecognizing = false

    companion object {
        private const val CHANNEL_ID = "jarvis_wake_channel"
        private const val NOTIFICATION_ID = 101
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, makeNotification("Starting wake detector..."))
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
        if (isRecognizing) return

        detector?.start(
            { score ->
                if (!isRecognizing) {
                    saveStatus("HEY JARVIS DETECTED! Listening...")
                    showWakeNotification("Listening for your command...")
                    beginSpeechRecognition()
                }
            },
            { status ->
                saveStatus(status)
                showWakeNotification(status)
            }
        )
    }

    private fun beginSpeechRecognition() {
        detector?.stop()
        isRecognizing = true

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            saveStatus("Speech recognition unavailable")
            resumeWakeDetection()
            return
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {

                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    saveStatus("JARVIS IS LISTENING...")
                }

                override fun onBeginningOfSpeech() {
                    saveStatus("Hearing your command...")
                }

                override fun onEndOfSpeech() {
                    saveStatus("Processing your command...")
                }

                override fun onResults(results: android.os.Bundle?) {
                    val words = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()

                    saveStatus(
                        if (!words.isNullOrBlank()) "YOU SAID: $words"
                        else "No speech recognized"
                    )

                    showWakeNotification(words ?: "No speech recognized")
                    resumeWakeDetection()
                }

                override fun onError(error: Int) {
                    saveStatus("Speech recognition error: $error")
                    resumeWakeDetection()
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }

            speechRecognizer?.startListening(intent)

        } catch (e: Exception) {
            saveStatus("Speech error: ${e.message}")
            resumeWakeDetection()
        }
    }

    private fun resumeWakeDetection() {
        if (!isRecognizing) return

        isRecognizing = false
        speechRecognizer?.destroy()
        speechRecognizer = null

        saveStatus("Restarting Hey JARVIS detection...")
        startWakeDetection()
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
            .edit().putString("status", value).apply()
    }

    private fun showWakeNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, makeNotification(message))
    }

    override fun onDestroy() {
        isRecognizing = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        detector?.stop()
        detector?.release()
        detector = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
