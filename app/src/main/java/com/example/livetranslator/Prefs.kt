package com.example.livetranslator

import android.content.Context

/** Small wrapper around SharedPreferences for the app settings. */
object Prefs {
    const val ENGINE_GEMINI = "gemini"
    const val ENGINE_OFFLINE = "offline"
    const val ENGINE_HYBRID = "hybrid"
    const val ENGINE_STREAM = "stream"

    const val WHISPER_BASE = "base"
    const val WHISPER_SMALL = "small"

    // Model IDs taken from the Gemini API models page. Editable in the app in case they change.
    const val DEFAULT_LIVE_MODEL = "gemini-3.5-flash-lite"
    const val DEFAULT_FILE_MODEL = "gemini-3.8-flash"
    const val STREAM_MODEL = "gemini-3.5-live-translate-preview"

    private fun sp(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun apiKey(ctx: Context): String = sp(ctx).getString("api_key", "")!!.trim()

    fun liveEngine(ctx: Context): String =
        sp(ctx).getString("live_engine", ENGINE_GEMINI) ?: ENGINE_GEMINI

    fun liveModel(ctx: Context): String =
        (sp(ctx).getString("live_model", "") ?: "").trim().ifEmpty { DEFAULT_LIVE_MODEL }

    fun fileModel(ctx: Context): String =
        (sp(ctx).getString("file_model", "") ?: "").trim().ifEmpty { DEFAULT_FILE_MODEL }

    fun style(ctx: Context): Style {
        val i = sp(ctx).getInt("style", 0)
        return Style.values().getOrElse(i) { Style.DETAILED }
    }

    /** User glossary: one "english = arabic" pair per line. */
    fun glossary(ctx: Context): String = sp(ctx).getString("glossary", "") ?: ""

    fun setGlossary(ctx: Context, v: String) {
        sp(ctx).edit().putString("glossary", v.trim().take(4000)).apply()
    }

    /** Text appended to every Gemini prompt so fixed terms are always translated the same way. */
    fun glossaryHint(ctx: Context): String {
        val g = glossary(ctx).trim()
        if (g.isEmpty()) return ""
        return "\n\nGlossary: always translate these terms exactly as given (original = Arabic):\n$g"
    }

    /** English words from the glossary, given to Whisper as a hint so it spells them correctly. */
    fun glossaryTerms(ctx: Context): String {
        val words = glossary(ctx).lines()
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return ""
        return words.joinToString(", ").take(400)
    }

    /** Language spoken in the videos (a code from Languages, or "auto"). */
    fun sourceLang(ctx: Context): String = sp(ctx).getString("source_lang", "en") ?: "en"

    /** Whisper model size for the hybrid engine: base (~60 MB) or small (~190 MB, more accurate). */
    fun whisperSize(ctx: Context): String =
        sp(ctx).getString("whisper_size", WHISPER_BASE) ?: WHISPER_BASE

    /** Subtitle text size in sp. */
    fun subtitleSize(ctx: Context): Int = sp(ctx).getInt("subtitle_size", 20)

    /** Show only the Arabic line (hide the original text) in the subtitle bar. */
    fun arabicOnly(ctx: Context): Boolean = sp(ctx).getBoolean("arabic_only", false)

    /** Distance of the subtitle bar from the bottom of the screen, in dp. */
    fun subtitleOffsetDp(ctx: Context): Int = sp(ctx).getInt("subtitle_offset", 110)

    fun setSubtitleOffsetDp(ctx: Context, v: Int) {
        sp(ctx).edit().putInt("subtitle_offset", v).apply()
    }

    fun saveExtra(ctx: Context, sourceLang: String, whisperSize: String, subtitleSize: Int, arabicOnly: Boolean) {
        sp(ctx).edit()
            .putString("source_lang", sourceLang)
            .putString("whisper_size", whisperSize)
            .putInt("subtitle_size", subtitleSize)
            .putBoolean("arabic_only", arabicOnly)
            .apply()
    }

    /** Manual sync correction in milliseconds (positive = subtitles appear earlier). */
    fun syncMs(ctx: Context): Int = sp(ctx).getInt("sync_ms", 0)

    fun setSyncMs(ctx: Context, v: Int) {
        sp(ctx).edit().putInt("sync_ms", v).apply()
    }

    fun save(
        ctx: Context,
        apiKey: String,
        engine: String,
        liveModel: String,
        fileModel: String,
        style: Style
    ) {
        sp(ctx).edit()
            .putString("api_key", apiKey.trim())
            .putString("live_engine", engine)
            .putString("live_model", liveModel.trim())
            .putString("file_model", fileModel.trim())
            .putInt("style", style.ordinal)
            .apply()
    }
}
