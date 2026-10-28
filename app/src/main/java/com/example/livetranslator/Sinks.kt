package com.example.livetranslator

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Offline engine: Vosk turns English speech into text; the service translates it with ML Kit. */
class VoskSink(
    modelPath: String,
    private val onText: (String, Boolean) -> Unit
) : AudioSink {
    private val model = Model(modelPath)
    private val recognizer = Recognizer(model, AudioUtil.SAMPLE_RATE.toFloat())

    override fun onAudio(buf: ShortArray, n: Int) {
        if (recognizer.acceptWaveForm(buf, n)) {
            val t = JSONObject(recognizer.result).optString("text")
            if (t.isNotBlank()) onText(t, true)
        } else {
            val p = JSONObject(recognizer.partialResult).optString("partial")
            if (p.isNotBlank()) onText(p, false)
        }
    }

    override fun close() {
        try {
            recognizer.close()
        } catch (_: Exception) {
        }
        try {
            model.close()
        } catch (_: Exception) {
        }
    }
}

/**
 * Gemini engine: cuts the audio into chunks of about 3-9 seconds at pauses in speech,
 * sends each chunk to Gemini, and reports the English text plus the Arabic translation.
 */
class GeminiSink(
    private val apiKey: String,
    private val model: String,
    private val onResult: (String, String) -> Unit,
    private val onError: (String) -> Unit
) : AudioSink {

    companion object {
        private const val PROMPT =
            "This is a short audio clip from an English-language video. " +
                "Transcribe what is said and translate it into Arabic. " +
                "Reply in exactly two lines:\n" +
                "EN: <what was said>\n" +
                "AR: <Arabic translation>\n" +
                "If there is no clear speech, reply with only: NONE"
    }

    private val sr = AudioUtil.SAMPLE_RATE
    private val maxLen = sr * 9
    private val minLen = sr * 3
    private val buf = ShortArray(maxLen)
    private var len = 0
    private var voiced = 0
    private var silentRun = 0
    private val exec = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)

    override fun onAudio(buf: ShortArray, n: Int) {
        var sum = 0.0
        for (i in 0 until n) sum += buf[i].toDouble() * buf[i].toDouble()
        val rms = Math.sqrt(sum / n)
        val silent = rms < 250.0

        val c = minOf(n, maxLen - len)
        System.arraycopy(buf, 0, this.buf, len, c)
        len += c
        if (silent) {
            silentRun += n
        } else {
            silentRun = 0
            voiced += n
        }
        if (len >= maxLen || (len >= minLen && silentRun >= sr * 6 / 10)) flush()
    }

    private fun flush() {
        val wav = if (voiced >= sr * 8 / 10 && pending.get() < 3) {
            AudioUtil.shortsToWav(buf, len)
        } else {
            null
        }
        len = 0
        voiced = 0
        silentRun = 0
        if (wav == null) return

        pending.incrementAndGet()
        exec.execute {
            try {
                val reply = GeminiClient.generate(
                    apiKey, model,
                    listOf(Part.Text(PROMPT), Part.Inline(wav, "audio/wav"))
                )
                val parsed = parse(reply)
                if (parsed != null) onResult(parsed.first, parsed.second)
            } catch (e: GeminiException) {
                onError("Gemini: " + (e.message ?: "").take(90))
                if (e.code == 429) {
                    try {
                        Thread.sleep(8000)
                    } catch (_: InterruptedException) {
                    }
                }
            } catch (e: Exception) {
                onError("خطأ: " + (e.message ?: "").take(90))
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    private fun parse(reply: String): Pair<String, String>? {
        val trimmed = reply.trim()
        if (trimmed.isEmpty() || trimmed.equals("NONE", ignoreCase = true)) return null
        var en = ""
        var ar = ""
        for (line in trimmed.lines()) {
            val l = line.trim()
            if (l.startsWith("EN:", ignoreCase = true)) en = l.substring(3).trim()
            else if (l.startsWith("AR:", ignoreCase = true)) ar = l.substring(3).trim()
        }
        if (en.isBlank() && ar.isBlank()) return Pair("", trimmed)
        return Pair(en, ar)
    }

    override fun close() {
        exec.shutdownNow()
    }
}
