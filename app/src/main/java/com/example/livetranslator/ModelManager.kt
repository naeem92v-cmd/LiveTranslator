package com.example.livetranslator

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Downloads and unpacks the small English Vosk model (~40 MB) on first run. */
object ModelManager {

    const val MODEL_NAME = "vosk-model-small-en-us-0.15"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"

    fun modelDir(ctx: Context): File = File(ctx.filesDir, MODEL_NAME)

    fun isReady(ctx: Context): Boolean = File(modelDir(ctx), "am/final.mdl").exists()

    fun download(ctx: Context, onStatus: (String) -> Unit) {
        modelDir(ctx).deleteRecursively()
        val zipFile = File(ctx.cacheDir, "$MODEL_NAME.zip")

        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != 200) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                zipFile.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onStatus("جاري تنزيل نموذج الصوت… $pct%")
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        onStatus("جاري فك الضغط…")
        val root = ctx.filesDir.canonicalFile
        ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val target = File(root, entry.name).canonicalFile
                // Zip-slip protection
                if (!target.path.startsWith(root.path + File.separator)) {
                    throw SecurityException("Bad zip entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zin.copyTo(it) }
                }
                zin.closeEntry()
            }
        }
        zipFile.delete()

        if (!isReady(ctx)) throw IllegalStateException("النموذج غير مكتمل بعد فك الضغط")
        onStatus("تم تجهيز نموذج الصوت ✓")
    }

    // ---------- Whisper (whisper.cpp) model, quantised q5_1 ----------
    // base ~60 MB, small ~190 MB (more accurate). The ".en" models are English-only and a bit
    // better at English; any other source language needs the multilingual model.

    private const val WHISPER_BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    fun whisperFileName(ctx: Context): String {
        val size = Prefs.whisperSize(ctx)
        val english = Prefs.sourceLang(ctx) == "en"
        return "ggml-$size" + (if (english) ".en" else "") + "-q5_1.bin"
    }

    fun whisperFile(ctx: Context): File = File(ctx.filesDir, whisperFileName(ctx))

    private fun whisperMinBytes(ctx: Context): Long =
        (if (Prefs.whisperSize(ctx) == Prefs.WHISPER_SMALL) 150L else 40L) * 1024 * 1024

    fun isWhisperReady(ctx: Context): Boolean = whisperFile(ctx).length() >= whisperMinBytes(ctx)

    fun downloadWhisper(ctx: Context, onStatus: (String) -> Unit) {
        val target = whisperFile(ctx)
        val minBytes = whisperMinBytes(ctx)
        val tmp = File(ctx.filesDir, target.name + ".part")
        tmp.delete()
        val conn = URL(WHISPER_BASE_URL + target.name).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onStatus("جاري تنزيل نموذج Whisper… $pct%")
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (tmp.length() < minBytes) {
            tmp.delete()
            throw IllegalStateException("الملف المنزَّل ناقص")
        }
        target.delete()
        if (!tmp.renameTo(target)) throw IllegalStateException("تعذّر حفظ النموذج")
        // only one Whisper model is kept on the phone
        ctx.filesDir.listFiles { f -> f.name.startsWith("ggml-") && f.name != target.name }
            ?.forEach { it.delete() }
        onStatus("تم تجهيز نموذج Whisper ✓")
    }
}
