package com.jarvis.assistant

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.openwakeword.OpenWakeWord
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationService
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

class WakeWordService : Service(), TextToSpeech.OnInitListener {

    private var detector: OpenWakeWord? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isRecognizing = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var authService: AuthorizationService? = null
    private val handler = Handler(Looper.getMainLooper())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    companion object {
        private const val CHANNEL_ID = "jarvis_wake_channel"
        private const val NOTIFICATION_ID = 101
        private const val STREAM_URL =
            "https://4rnk558awb.execute-api.ap-southeast-2.amazonaws.com/prod/stream"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = TextToSpeech(this, this)
        authService = AuthorizationService(this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("en", "IN")
            ttsReady = true
        }
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
            {
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

                    speechRecognizer?.destroy()
                    speechRecognizer = null

                    if (!words.isNullOrBlank()) {
                        saveStatus("YOU SAID: $words")
                        showWakeNotification("Sending your command...")
                        sendToJarvis(words)
                    } else {
                        saveStatus("No speech recognized")
                        speak("I didn't hear that. Please try again.")
                        handler.postDelayed({ resumeWakeDetection() }, 3000)
                    }
                }

                override fun onError(error: Int) {
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                    saveStatus("SPEECH RECOGNITION ERROR: $error")
                    showWakeNotification("Speech error: $error")
                    handler.postDelayed({ resumeWakeDetection() }, 2000)
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
            handler.postDelayed({ resumeWakeDetection() }, 2000)
        }
    }

    private fun sendToJarvis(message: String) {
        val savedState = getSharedPreferences("jarvis_auth", MODE_PRIVATE)
            .getString("authState", null)

        if (savedState.isNullOrBlank()) {
            saveStatus("Please login to JARVIS first")
            speak("Please login to JARVIS first.")
            handler.postDelayed({ resumeWakeDetection() }, 4000)
            return
        }

        try {
            val authState = AuthState.jsonDeserialize(savedState)

            authState.performActionWithFreshTokens(authService!!) {
                    accessToken, _, exception ->

                getSharedPreferences("jarvis_auth", MODE_PRIVATE)
                    .edit()
                    .putString("authState", authState.jsonSerializeString())
                    .apply()

                if (exception != null || accessToken.isNullOrBlank()) {
                    saveStatus("Authentication failed. Please login again.")
                    speak("Authentication failed. Please login again.")
                    handler.postDelayed({ resumeWakeDetection() }, 4000)
                    return@performActionWithFreshTokens
                }

                streamResponse(message, accessToken)
            }

        } catch (e: Exception) {
            saveStatus("Authentication error: ${e.message}")
            speak("Please login to JARVIS again.")
            handler.postDelayed({ resumeWakeDetection() }, 4000)
        }
    }

    private fun streamResponse(message: String, token: String) {
        val json = JSONObject()
            .put("message", message)
            .toString()

        val body = json.toRequestBody(
            "application/json; charset=utf-8".toMediaType()
        )

        val request = Request.Builder()
            .url(STREAM_URL)
            .post(body)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .build()

        saveStatus("JARVIS is thinking...")
        showWakeNotification("JARVIS is thinking...")

        httpClient.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                saveStatus("Network error: ${e.message}")
                speak("I couldn't connect to the server.")
                handler.postDelayed({ resumeWakeDetection() }, 4000)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        saveStatus("AWS API error: ${it.code}")
                        speak("The server returned an error.")
                        handler.postDelayed({ resumeWakeDetection() }, 4000)
                        return
                    }

                    val input = it.body?.byteStream()

                    if (input == null) {
                        saveStatus("Empty server response")
                        handler.postDelayed({ resumeWakeDetection() }, 2000)
                        return
                    }

                    saveStatus("JARVIS is responding...")

                    try {
                        val reader = input.bufferedReader()
                        val buffer = CharArray(512)
                        val pending = StringBuilder()
                        var spoken = false

                        while (true) {
                            val count = reader.read(buffer)
                            if (count == -1) break

                            pending.append(buffer, 0, count)

                            while (true) {
                                val boundary = pending.indexOfAny(
                                    charArrayOf('.', '!', '?', '\n')
                                )

                                if (boundary >= 0) {
                                    val phrase = pending.substring(0, boundary + 1).trim()
                                    pending.delete(0, boundary + 1)

                                    if (phrase.isNotBlank()) {
                                        speak(phrase)
                                        spoken = true
                                    }
                                } else if (pending.length >= 100) {
                                    val split = pending.lastIndexOf(" ", 100)
                                    if (split > 0) {
                                        val phrase = pending.substring(0, split).trim()
                                        pending.delete(0, split + 1)
                                        if (phrase.isNotBlank()) {
                                            speak(phrase)
                                            spoken = true
                                        }
                                    } else {
                                        break
                                    }
                                } else {
                                    break
                                }
                            }
                        }

                        val remaining = pending.toString().trim()
                        if (remaining.isNotBlank()) {
                            speak(remaining)
                            spoken = true
                        }

                        saveStatus(
                            if (spoken) "JARVIS response completed"
                            else "JARVIS returned an empty response"
                        )

                        handler.postDelayed(
                            { resumeWakeDetection() },
                            2500
                        )

                    } catch (e: Exception) {
                        saveStatus("Response error: ${e.message}")
                        handler.postDelayed({ resumeWakeDetection() }, 3000)
                    }
                }
            }
        })
    }

    private fun speak(text: String) {
        handler.post {
            if (ttsReady) {
                tts?.speak(
                    text,
                    TextToSpeech.QUEUE_ADD,
                    null,
                    "jarvis_${System.currentTimeMillis()}"
                )
            }
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
            .edit()
            .putString("status", value)
            .apply()
    }

    private fun showWakeNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, makeNotification(message))
    }

    override fun onDestroy() {
        isRecognizing = false
        httpClient.dispatcher.cancelAll()
        speechRecognizer?.destroy()
        speechRecognizer = null
        detector?.stop()
        detector?.release()
        detector = null
        tts?.stop()
        tts?.shutdown()
        authService?.dispose()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
