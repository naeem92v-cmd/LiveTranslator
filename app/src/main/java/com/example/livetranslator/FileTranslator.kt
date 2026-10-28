package com.example.livetranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

enum class Style { DETAILED, TIMESTAMPS, SUMMARY }

/** Translates a whole audio/video file through the Gemini API (upload -> translate). */
object FileTranslator {

    private fun prompt(style: Style): String = when (style) {
        Style.DETAILED ->
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
            val text = GeminiClient.generate(
                key,
                Prefs.fileModel(ctx),
                listOf(Part.Text(prompt(style)), Part.FileRef(uploaded.uri, mime, kind))
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
