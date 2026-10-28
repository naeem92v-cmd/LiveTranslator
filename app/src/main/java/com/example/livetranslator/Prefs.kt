package com.example.livetranslator

import android.content.Context

/** Small wrapper around SharedPreferences for the app settings. */
object Prefs {
    const val ENGINE_GEMINI = "gemini"
    const val ENGINE_OFFLINE = "offline"

    // Model IDs taken from the Gemini API models page. Editable in the app in case they change.
    const val DEFAULT_LIVE_MODEL = "gemini-3.5-flash-lite"
    const val DEFAULT_FILE_MODEL = "gemini-3.8-flash"

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
