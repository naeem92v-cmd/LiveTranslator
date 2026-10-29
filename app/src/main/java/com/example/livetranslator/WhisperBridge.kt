package com.example.livetranslator

/** Kotlin side of whisper_jni.cpp (offline English speech recognition with whisper.cpp). */
object WhisperBridge {

    val available: Boolean = try {
        System.loadLibrary("whisperjni")
        true
    } catch (_: Throwable) {
        false
    }

    private external fun nativeInit(path: String): Long
    private external fun nativeFree(ptr: Long)
    private external fun nativeTranscribe(
        ptr: Long, audio: FloatArray, n: Int, threads: Int, prompt: String?, lang: String
    ): ByteArray?

    class Context internal constructor(private var ptr: Long) {
        @Synchronized
        fun transcribe(audio: FloatArray, n: Int, threads: Int, prompt: String?, lang: String): String? {
            if (ptr == 0L) return null
            val bytes = nativeTranscribe(ptr, audio, n, threads, prompt, lang) ?: return null
            return String(bytes, Charsets.UTF_8).trim()
        }

        @Synchronized
        fun close() {
            if (ptr != 0L) {
                nativeFree(ptr)
                ptr = 0L
            }
        }
    }

    fun load(path: String): Context? {
        if (!available) return null
        val p = nativeInit(path)
        return if (p == 0L) null else Context(p)
    }
}
