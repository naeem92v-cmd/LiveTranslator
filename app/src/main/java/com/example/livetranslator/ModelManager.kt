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
}
