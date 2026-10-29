package com.example.livetranslator

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hybrid engine: whisper.cpp recognises the English speech on the phone (no audio leaves the device),
 * then only the text is translated: with Gemini when a key is set, otherwise ML Kit (via onFallback).
 */
class WhisperSink(
    modelPath: String,
    sourceLang: String,
    private val apiKey: String,
    private val model: String,
    private val glossaryHint: String,
    private val glossaryTerms: String,
    private val onEnglish: (String) -> Unit,
    private val onResult: (String, String) -> Unit,
    private val onFallback: (String) -> Unit,
    private val onError: (String) -> Unit
) : AudioSink {

    private val ctx: WhisperBridge.Context =
        WhisperBridge.load(modelPath) ?: throw IllegalStateException("تعذّر تحميل نموذج Whisper")

    private val whisperLang = sourceLang
    private val langName = Languages.promptName(sourceLang)
    private val sr = AudioUtil.SAMPLE_RATE
    private val maxLen = sr * 8
    private val minLen = sr * 2
    private val buf = ShortArray(maxLen)
    private var len = 0
    private var voiced = 0
    private var silentRun = 0
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    private val asr = Executors.newSingleThreadExecutor()
    private val tr = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)
    @Volatile private var previous = ""
    @Volatile private var closed = false

    override fun onAudio(buf: ShortArray, n: Int) {
        var sum = 0.0
        for (i in 0 until n) sum += buf[i].toDouble() * buf[i].toDouble()
        val silent = Math.sqrt(sum / n) < 250.0

        val c = minOf(n, maxLen - len)
        System.arraycopy(buf, 0, this.buf, len, c)
        len += c
        if (silent) {
            silentRun += n
        } else {
            silentRun = 0
            voiced += n
        }
        if (len >= maxLen || (len >= minLen && silentRun >= sr / 2)) flush()
    }

    private fun flush() {
        val ok = voiced >= sr * 6 / 10 && pending.get() < 3
        val audio = if (ok) FloatArray(len) { this.buf[it] / 32768f } else null
        val n = len
        len = 0
        voiced = 0
        silentRun = 0
        if (audio == null) return

        pending.incrementAndGet()
        asr.execute {
            try {
                if (closed) return@execute
                val raw = ctx.transcribe(audio, n, threads, glossaryTerms.ifBlank { null }, whisperLang)
                val text = clean(raw)
                if (text.isNotEmpty()) {
                    onEnglish(text)
                    translate(text)
                }
            } catch (e: Throwable) {
                onError("Whisper: " + (e.message ?: "").take(90))
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    /** Drops Whisper's non-speech markers such as [BLANK_AUDIO] or (music). */
    private fun clean(raw: String?): String {
        if (raw == null) return ""
        var t = raw.trim()
        t = t.replace(Regex("\\[[^\\]]*\\]"), " ").replace(Regex("\\([^)]*\\)"), " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        if (t.length < 2 || !t.any { it.isLetter() }) return ""
        return t
    }

    private fun translate(en: String) {
        if (apiKey.isBlank()) {
            onFallback(en)
            return
        }
        tr.execute {
            if (closed) return@execute
            try {
                val prompt = "Translate the sentence (in $langName) into natural Arabic. " +
                    "Reply with ONLY the Arabic translation, nothing else.\n" +
                    (if (previous.isNotBlank()) "Previous sentence (context only, do not translate): $previous\n" else "") +
                    "Sentence: $en" + glossaryHint
                val ar = GeminiClient.generate(apiKey, model, listOf(Part.Text(prompt))).trim()
                previous = en
                if (ar.isNotEmpty()) onResult(en, ar) else onFallback(en)
            } catch (e: GeminiException) {
                onError("Gemini: " + (e.message ?: "").take(80))
                onFallback(en)
                if (e.code == 429) {
                    try {
                        Thread.sleep(6000)
                    } catch (_: InterruptedException) {
                    }
                }
            } catch (e: Exception) {
                onFallback(en)
            }
        }
    }

    override fun close() {
        closed = true
        asr.shutdownNow()
        tr.shutdownNow()
        // wait for a possibly running native call before freeing (close() is synchronized in Context)
        ctx.close()
    }
}
