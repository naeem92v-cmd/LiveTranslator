package com.example.livetranslator

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** What one Live API server message carries (only the parts this app uses). */
class LiveMessage(
    val setupComplete: Boolean,
    val sourceText: String,
    val arabicText: String,
    val turnComplete: Boolean,
    val goAway: Boolean
)

object LiveMessages {
    /** Setup for the speech translation model: audio in, Arabic out, with both transcripts. */
    fun setup(model: String): String = JSONObject().put(
        "setup",
        JSONObject()
            .put("model", "models/$model")
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", org.json.JSONArray().put("AUDIO"))
                    .put("inputAudioTranscription", JSONObject())
                    .put("outputAudioTranscription", JSONObject())
                    .put(
                        "translationConfig",
                        JSONObject().put("targetLanguageCode", "ar").put("echoTargetLanguage", false)
                    )
            )
    ).toString()

    fun parse(json: String): LiveMessage {
        val o = JSONObject(json)
        val sc = o.optJSONObject("serverContent")
        return LiveMessage(
            setupComplete = o.has("setupComplete"),
            sourceText = sc?.optJSONObject("inputTranscription")?.optString("text", "") ?: "",
            arabicText = sc?.optJSONObject("outputTranscription")?.optString("text", "") ?: "",
            turnComplete = sc != null && (sc.optBoolean("turnComplete") || sc.optBoolean("generationComplete")),
            goAway = o.has("goAway")
        )
    }
}

/**
 * Streaming engine: the captured audio is sent continuously (100 ms chunks) to the Gemini Live
 * speech-translation model over a WebSocket, and the transcripts come back as they are spoken,
 * so the delay is far shorter than with clip-by-clip translation. The translated audio is ignored.
 * The connection is re-opened automatically when the server ends the session.
 */
class LiveTranslateSink(
    private val apiKey: String,
    private val model: String,
    private val onText: (String, String) -> Unit,
    private val onStatus: (String) -> Unit
) : AudioSink {

    companion object {
        private const val CHUNK_SAMPLES = AudioUtil.SAMPLE_RATE / 10   // 100 ms
        private const val MAX_SHOWN = 160
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val sender = Executors.newSingleThreadScheduledExecutor()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var closed = false
    private var failures = 0

    private val chunk = ShortArray(CHUNK_SAMPLES)
    private var fill = 0

    // text of the current turn; a finished turn is replaced when the next one starts
    private val src = StringBuilder()
    private val ar = StringBuilder()
    private var turnDone = false

    init {
        connect()
    }

    private fun connect() {
        if (closed) return
        ready = false
        val req = Request.Builder()
            .url(
                "wss://generativelanguage.googleapis.com/ws/" +
                    "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
            )
            .build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(LiveMessages.setup(model))
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(webSocket, bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (webSocket !== ws) return
                if (code != 1000 && reason.isNotBlank()) onStatus("Gemini Live: " + reason.take(90))
                reconnectLater()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== ws) return
                val code = response?.code
                onStatus("Gemini Live: " + (if (code != null) "HTTP $code " else "") + (t.message ?: "").take(80))
                reconnectLater()
            }
        })
    }

    /** Only the current socket may trigger a reconnect (old ones still report their closing). */
    private fun reconnectLater() {
        ready = false
        ws = null
        if (closed) return
        failures++
        // back off on repeated failures (wrong key, quota) so the phone does not hammer the server
        val delay = minOf(2000L * failures, 30000L)
        try {
            sender.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
    }

    private fun handle(socket: WebSocket, json: String) {
        if (socket !== ws) return
        val m = try {
            LiveMessages.parse(json)
        } catch (_: Exception) {
            return
        }
        if (m.setupComplete) {
            ready = true
            failures = 0
            return
        }
        if (m.goAway) {
            // the server is about to end this session: open a fresh one, then drop the old one
            ws = null
            connect()
            socket.close(1000, null)
            return
        }
        if (m.sourceText.isEmpty() && m.arabicText.isEmpty() && !m.turnComplete) return
        synchronized(this) {
            if (turnDone && (m.sourceText.isNotEmpty() || m.arabicText.isNotEmpty())) {
                src.setLength(0)
                ar.setLength(0)
                turnDone = false
            }
            src.append(m.sourceText)
            ar.append(m.arabicText)
            if (m.turnComplete) turnDone = true
            if (src.isNotEmpty() || ar.isNotEmpty()) {
                onText(tail(src), tail(ar))
            }
        }
    }

    private fun tail(sb: StringBuilder): String {
        val s = sb.toString().trim()
        if (s.length <= MAX_SHOWN) return s
        val cut = s.indexOf(' ', s.length - MAX_SHOWN)
        return "…" + s.substring(if (cut > 0) cut + 1 else s.length - MAX_SHOWN)
    }

    override fun onAudio(buf: ShortArray, n: Int) {
        var i = 0
        while (i < n) {
            val c = minOf(n - i, CHUNK_SAMPLES - fill)
            System.arraycopy(buf, i, chunk, fill, c)
            fill += c
            i += c
            if (fill == CHUNK_SAMPLES) {
                fill = 0
                if (ready) send(chunk)
            }
        }
    }

    private fun send(samples: ShortArray) {
        val bytes = ByteArray(samples.size * 2)
        for (k in samples.indices) {
            val v = samples[k].toInt()
            bytes[2 * k] = (v and 0xFF).toByte()
            bytes[2 * k + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val msg = JSONObject().put(
            "realtimeInput",
            JSONObject().put(
                "audio",
                JSONObject()
                    .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .put("mimeType", "audio/pcm;rate=16000")
            )
        )
        ws?.send(msg.toString())
    }

    override fun close() {
        closed = true
        ready = false
        try {
            ws?.close(1000, null)
        } catch (_: Exception) {
        }
        sender.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
