package com.example.livetranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

/** SESSION: timed lines + fingerprint, so the video can be followed later (added last: ordinals are saved). */
enum class Style { DETAILED, TIMESTAMPS, SUMMARY, SESSION }

/** Translates a whole audio/video file through the Gemini API (upload -> translate). */
object FileTranslator {

    private fun prompt(style: Style): String = when (style) {
        Style.DETAILED, Style.SESSION ->
            "Translate everything that is said in this recording into Arabic, in full and in detail. " +
                "Do not summarize or skip anything. Keep names, numbers and technical terms accurate " +
                "and write natural, clear Arabic. If the speech is not English, name the language first. " +
                "The file may be a video: use only the speech."
        Style.TIMESTAMPS ->
            "Translate everything that is said in this recording into Arabic, in full and in detail. " +
                "Start every paragraph with an approximate timestamp like [00:00]. " +
                "Do not summarize or skip anything. Keep names, numbers and technical terms accurate. " +
                "If the speech is not English, name the language first. The file may be a video: use only the speech."
        Style.SUMMARY ->
            "Summarize what is said in this recording in Arabic: one short paragraph, " +
                "then five bullet points with the key ideas. The file may be a video: use only the speech."
    }

    fun lastFile(ctx: Context): File = File(ctx.filesDir, "last_translation.txt")

    /**
     * Sends a recording to Gemini and returns timed, speaker-labelled segments
     * (English + Arabic). Blocks, so call it from a background thread.
     */
    fun runSegments(
        ctx: Context,
        wav: File,
        durationSec: Double,
        onsets: List<Double>,
        onStatus: (String) -> Unit
    ): List<Segment> {
        val key = Prefs.apiKey(ctx)
        if (key.isBlank()) throw GeminiException(0, "أدخل مفتاح Gemini API في الإعدادات أولاً")

        // Long recordings go in parts of ~10 minutes (the reply length is limited)
        val cuts = SegmentTools.chooseCuts(durationSec, onsets)
        val bounds = listOf(0.0) + cuts + listOf(durationSec)
        val parts = bounds.size - 1
        val out = ArrayList<Segment>()
        for (i in 0 until parts) {
            val label = if (parts > 1) " (الجزء ${i + 1} من $parts)" else ""
            val partFile = if (parts > 1) {
                File(ctx.cacheDir, "part_${System.currentTimeMillis()}_$i.wav").also {
                    AudioUtil.sliceWav(wav, bounds[i], bounds[i + 1], it)
                }
            } else {
                wav
            }
            try {
                onStatus("يرفع التسجيل$label… 0%")
                val up = GeminiClient.uploadFile(key, partFile, "audio/wav") { pct ->
                    onStatus("يرفع التسجيل$label… $pct%")
                }
                onStatus("Gemini يحلل المتحدثين ويترجم$label… قد يستغرق عدة دقائق")
                val text = generateWithRetry(
                    key,
                    Prefs.fileModel(ctx),
                    listOf(
                        Part.Text(SegmentTools.PROMPT + Prefs.glossaryHint(ctx)),
                        Part.FileRef(up.uri, "audio/wav", "audio")
                    )
                )
                out.addAll(SegmentTools.shift(SegmentTools.parse(text), bounds[i]))
            } finally {
                if (partFile != wav) partFile.delete()
            }
        }
        if (out.isEmpty()) throw GeminiException(0, "لم يُكتشف كلام في التسجيل")
        out.sortBy { it.start }
        return out
    }

    /** Rate limits and busy servers are common on the free tier: wait and try again twice. */
    private fun generateWithRetry(key: String, model: String, parts: List<Part>): String {
        var attempt = 0
        while (true) {
            try {
                return GeminiClient.generate(key, model, parts)
            } catch (e: GeminiException) {
                if (attempt >= 2 || e.code !in listOf(429, 500, 503)) throw e
                attempt++
                Thread.sleep(15000L * attempt)
            }
        }
    }

    /**
     * Translates a picked/shared file into a session (timed lines + audio fingerprint), so that
     * playing the same video later can be followed like a recording. Returns the session id.
     */
    fun runSession(ctx: Context, uri: Uri, name: String?, onStatus: (String) -> Unit): String {
        val key = Prefs.apiKey(ctx)
        if (key.isBlank()) throw GeminiException(0, "أدخل مفتاح Gemini API في الإعدادات أولاً")

        val wav = File(ctx.cacheDir, "file_${System.currentTimeMillis()}.wav")
        try {
            val rec = SessionRecorder(wav)
            // same 2048-sample blocks as the live capture: the onset detector counts blocks
            val block = ShortArray(2048)
            var fill = 0
            val feed = { b: ShortArray, n: Int ->
                var i = 0
                while (i < n) {
                    val c = minOf(n - i, block.size - fill)
                    System.arraycopy(b, i, block, fill, c)
                    fill += c
                    i += c
                    if (fill == block.size) {
                        rec.onAudio(block, fill)
                        fill = 0
                    }
                }
            }
            onStatus("يحلل صوت الملف… 0%")
            val ok = try {
                val decoded = AudioDecoder.decode(ctx, uri, feed) { pct ->
                    onStatus("يحلل صوت الملف… $pct%")
                }
                if (fill > 0) rec.onAudio(block, fill)
                decoded
            } finally {
                rec.close()
            }
            if (!ok || rec.durationSec < 3.0) throw GeminiException(0, "تعذّرت قراءة صوت الملف")

            val raw = runSegments(ctx, wav, rec.durationSec, rec.onsets(), onStatus)
            val segs = SegmentTools.snap(raw, rec.onsets())
            val id = SessionStore.save(ctx, segs, rec.durationSec, rec.fingerprint(), name)
            lastFile(ctx).writeText(SegmentTools.formatText(segs))
            return id
        } finally {
            wav.delete()
        }
    }

    /**
     * Either [wav] (a recording made by the app) or [uri] (a picked/shared file) must be given.
     * Blocks, so call it from a background thread. Returns the Arabic translation.
     */
    fun run(ctx: Context, uri: Uri?, wav: File?, style: Style, onStatus: (String) -> Unit): String {
        val key = Prefs.apiKey(ctx)
        if (key.isBlank()) throw GeminiException(0, "أدخل مفتاح Gemini API في الإعدادات أولاً")

        val temps = mutableListOf<File>()
        try {
            val (file, mime, kind) = prepare(ctx, uri, wav, temps, onStatus)

            onStatus("يرفع الملف… 0%")
            val uploaded = GeminiClient.uploadFile(key, file, mime) { pct ->
                onStatus("يرفع الملف… $pct%")
            }

            onStatus("Gemini يترجم… قد يستغرق دقيقة")
            val text = generateWithRetry(
                key,
                Prefs.fileModel(ctx),
                listOf(Part.Text(prompt(style) + Prefs.glossaryHint(ctx)), Part.FileRef(uploaded.uri, mime, kind))
            )
            if (text.isBlank()) throw GeminiException(0, "لم يرجع Gemini أي نص")

            lastFile(ctx).writeText(text)
            return text
        } finally {
            for (f in temps) f.delete()
        }
    }

    private fun prepare(
        ctx: Context,
        uri: Uri?,
        wav: File?,
        temps: MutableList<File>,
        onStatus: (String) -> Unit
    ): Triple<File, String, String> {
        if (wav != null) return Triple(wav, "audio/wav", "audio")
        if (uri == null) throw IllegalArgumentException("لا يوجد ملف")

        onStatus("يستخرج الصوت من الفيديو…")
        val stamp = System.currentTimeMillis()
        val extracted = File(ctx.cacheDir, "extracted_$stamp.m4a")
        temps.add(extracted)
        if (AudioUtil.extractAudio(ctx, uri, extracted)) {
            return Triple(extracted, "audio/m4a", "audio")
        }

        // No AAC track we can copy: upload the original file as-is
        onStatus("يجهّز الملف الأصلي…")
        val type = ctx.contentResolver.getType(uri) ?: "video/mp4"
        val copy = File(ctx.cacheDir, "input_$stamp")
        temps.add(copy)
        val input = ctx.contentResolver.openInputStream(uri)
            ?: throw GeminiException(0, "تعذّرت قراءة الملف")
        input.use { i -> copy.outputStream().use { o -> i.copyTo(o) } }
        val kind = if (type.startsWith("audio/")) "audio" else "video"
        return Triple(copy, type, kind)
    }
}

object Notifier {
    private const val CHANNEL = "results"

    fun result(ctx: Context, title: String, text: String) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "نتائج الترجمة", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val pi = PendingIntent.getActivity(
                ctx, 1,
                Intent(ctx, ResultActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(2, n)
        } catch (_: Exception) {
        }
    }
}
