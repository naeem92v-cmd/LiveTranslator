// Thin JNI layer over whisper.cpp. Kotlin side: WhisperBridge.kt
//
// Text is returned as UTF-8 bytes (jbyteArray) and decoded in Kotlin, because a token boundary
// can split a multi-byte character and NewStringUTF() does not tolerate invalid UTF-8.

#include <jni.h>

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_livetranslator_WhisperBridge_nativeInit(JNIEnv *env, jobject /*thiz*/, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    if (p == nullptr) return 0;
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cparams);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_example_livetranslator_WhisperBridge_nativeFree(JNIEnv * /*env*/, jobject /*thiz*/, jlong ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx != nullptr) whisper_free(ctx);
}

// Returns the recognised text as UTF-8 bytes, or null if whisper_full failed.
JNIEXPORT jbyteArray JNICALL
Java_com_example_livetranslator_WhisperBridge_nativeTranscribe(
        JNIEnv *env, jobject /*thiz*/, jlong ptr, jfloatArray audio, jint nSamples,
        jint threads, jstring prompt, jstring lang) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx == nullptr || audio == nullptr || nSamples <= 0) return nullptr;

    jfloat *data = env->GetFloatArrayElements(audio, nullptr);
    if (data == nullptr) return nullptr;

    std::string promptStr;
    if (prompt != nullptr) {
        const char *pc = env->GetStringUTFChars(prompt, nullptr);
        if (pc != nullptr) {
            promptStr = pc;
            env->ReleaseStringUTFChars(prompt, pc);
        }
    }

    // "en", "tr", ... or "auto" to let Whisper detect the language
    std::string langStr = "en";
    if (lang != nullptr) {
        const char *lc = env->GetStringUTFChars(lang, nullptr);
        if (lc != nullptr) {
            if (*lc) langStr = lc;
            env->ReleaseStringUTFChars(lang, lc);
        }
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = langStr.c_str();
    params.n_threads = threads > 0 ? threads : 4;
    params.no_context = true;
    params.no_timestamps = true;
    params.single_segment = true;
    params.suppress_nst = true;
    params.temperature_inc = 0.0f;  // no slow re-decoding at higher temperatures
    if (!promptStr.empty()) params.initial_prompt = promptStr.c_str();

    const int rc = whisper_full(ctx, params, data, nSamples);
    env->ReleaseFloatArrayElements(audio, data, JNI_ABORT);
    if (rc != 0) return nullptr;

    std::string text;
    const int n = whisper_full_n_segments(ctx);
    for (int i = 0; i < n; i++) {
        const char *seg = whisper_full_get_segment_text(ctx, i);
        if (seg != nullptr) text += seg;
    }

    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    if (out == nullptr) return nullptr;
    if (!text.empty()) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()),
                                reinterpret_cast<const jbyte *>(text.data()));
    }
    return out;
}

}  // extern "C"
