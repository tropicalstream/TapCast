package com.tapcast.app

import android.content.Context
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject

/**
 * Voice search: record from the glasses' mic (MediaRecorder → AAC/M4A), then
 * transcribe with Groq Whisper. Same pattern proven in SmartView/TapLinkX3.
 * The Groq key lives on-device only, entered masked in Settings.
 */
object GroqSpeech {
    private const val TAG = "TapCast"
    private const val STT_MODEL = "whisper-large-v3-turbo"

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())

    class Recorder(private val context: Context) {
        private var recorder: MediaRecorder? = null
        private var file: File? = null
        @Volatile var isRecording = false
            private set

        fun start(): Boolean {
            stopInternal()
            return runCatching {
                val f = File.createTempFile("rec_", ".m4a", context.cacheDir)
                @Suppress("DEPRECATION")
                val r = MediaRecorder()
                r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(44100)
                r.setAudioEncodingBitRate(128000)
                r.setOutputFile(f.absolutePath)
                r.prepare()
                r.start()
                recorder = r; file = f; isRecording = true
                true
            }.onFailure {
                Log.w(TAG, "recorder start failed: ${it.message}")
                stopInternal()
            }.getOrDefault(false)
        }

        /** Stop and return the recording (null if nothing usable was captured). */
        fun stop(): File? {
            val f = file
            stopInternal()
            return f?.takeIf { it.exists() && it.length() > 1200 }   // ignore blips
        }

        private fun stopInternal() {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recorder = null
            isRecording = false
        }
    }

    /** onResult(text, error): text non-null on success; error names the real failure. */
    fun transcribe(key: String, audio: File, onResult: (text: String?, error: String?) -> Unit) {
        if (key.isBlank()) { main.post { onResult(null, "Add a Groq API key in Settings for voice search") }; return }
        Thread {
            var errMsg: String? = null
            val text = runCatching {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", audio.name, audio.asRequestBody("audio/m4a".toMediaType()))
                    .addFormDataPart("model", STT_MODEL)
                    .addFormDataPart("response_format", "json")
                    .build()
                val req = Request.Builder()
                    .url("https://api.groq.com/openai/v1/audio/transcriptions")
                    .header("Authorization", "Bearer $key")
                    .post(body)
                    .build()
                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        errMsg = when (resp.code) {
                            401, 403 -> "Invalid Groq API key"
                            429 -> "Groq rate limit — wait a moment"
                            else -> "Speech service error (HTTP ${resp.code})"
                        }
                        throw IOException("HTTP ${resp.code}")
                    }
                    JSONObject(raw).optString("text", "").trim()
                }
            }.onFailure { e ->
                Log.w(TAG, "transcribe failed: ${e.message}")
                if (errMsg == null) {
                    val m = e.message.orEmpty()
                    errMsg = if (e is java.net.UnknownHostException || e is java.net.SocketTimeoutException ||
                        m.contains("Unable to resolve host") || m.contains("timeout", true))
                        "No internet connection" else "Speech error — try again"
                }
            }.getOrNull()
            runCatching { audio.delete() }
            val finalText = text?.takeIf { it.isNotEmpty() }
            main.post { onResult(finalText, if (finalText == null) errMsg else null) }
        }.start()
    }
}
