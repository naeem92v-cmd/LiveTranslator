package com.example.livetranslator

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

class GeminiException(val code: Int, message: String) : Exception(message)

sealed class Part {
    class Text(val text: String) : Part()
    class Inline(val bytes: ByteArray, val mime: String) : Part()
    /** kind is "audio" or "video". */
    class FileRef(val uri: String, val mime: String, val kind: String) : Part()
}

class UploadedFile(val uri: String, val name: String)

/**
 * Minimal Gemini REST client (no SDK).
 * Uses the Interactions endpoint described in the current docs and falls back to the
 * older generateContent endpoint if the first one is rejected with 400/404.
 */
object GeminiClient {
    private const val BASE = "https://generativelanguage.googleapis.com"

    fun generate(apiKey: String, model: String, parts: List<Part>): String {
        var firstError: GeminiException? = null
        try {
            val text = extractText(post("$BASE/v1beta/interactions", apiKey, interactionsBody(model, parts)))
            if (text.isNotBlank()) return text
        } catch (e: GeminiException) {
            if (e.code != 400 && e.code != 404) throw e
            firstError = e
        }
        try {
            return extractText(post("$BASE/v1beta/models/$model:generateContent", apiKey, legacyBody(parts)))
        } catch (e: GeminiException) {
            val extra = firstError?.message
            throw GeminiException(e.code, (e.message ?: "") + (if (extra != null) " | interactions: $extra" else ""))
        }
    }

    // ---------- Files API ----------

    fun uploadFile(apiKey: String, file: File, mime: String, onProgress: (Int) -> Unit): UploadedFile {
        val size = file.length()
        if (size <= 0L) throw GeminiException(0, "الملف فارغ")

        // Step 1: start a resumable upload
        var uploadUrl: String? = null
        val start = URL("$BASE/upload/v1beta/files").openConnection() as HttpURLConnection
        try {
            start.requestMethod = "POST"
            start.connectTimeout = 15000
            start.readTimeout = 60000
            start.doOutput = true
            start.setRequestProperty("x-goog-api-key", apiKey)
            start.setRequestProperty("X-Goog-Upload-Protocol", "resumable")
            start.setRequestProperty("X-Goog-Upload-Command", "start")
            start.setRequestProperty("X-Goog-Upload-Header-Content-Length", size.toString())
            start.setRequestProperty("X-Goog-Upload-Header-Content-Type", mime)
            start.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject()
                .put("file", JSONObject().put("display_name", file.name))
                .toString().toByteArray(Charsets.UTF_8)
            start.setFixedLengthStreamingMode(body.size)
            start.outputStream.use { it.write(body) }
            val code = start.responseCode
            if (code !in 200..299) {
                val t = start.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                throw GeminiException(code, errorMessage(code, t))
            }
            uploadUrl = start.getHeaderField("x-goog-upload-url")
        } finally {
            start.disconnect()
        }
        val url = uploadUrl ?: throw GeminiException(0, "لم يرجع الخادم رابط الرفع")

        // Step 2: send the bytes and finalize
        val up = URL(url).openConnection() as HttpURLConnection
        val responseText: String
        try {
            up.requestMethod = "POST"
            up.connectTimeout = 15000
            up.readTimeout = 300000
            up.doOutput = true
            up.setRequestProperty("X-Goog-Upload-Offset", "0")
            up.setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
            up.setFixedLengthStreamingMode(size)
            up.outputStream.use { out ->
                FileInputStream(file).use { input ->
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        val pct = (sent * 100 / size).toInt()
                        if (pct != last) {
                            last = pct
                            onProgress(pct)
                        }
                    }
                }
            }
            responseText = readResponse(up)
        } finally {
            up.disconnect()
        }

        val f = JSONObject(responseText).getJSONObject("file")
        val name = f.getString("name")
        val uri = f.getString("uri")

        // Audio is usually ACTIVE immediately, video may need processing time
        var state = f.optString("state", "ACTIVE")
        var tries = 0
        while (state == "PROCESSING" && tries < 60) {
            Thread.sleep(2000)
            state = fileState(apiKey, name)
            tries++
        }
        if (state == "FAILED") throw GeminiException(0, "فشلت معالجة الملف عند Gemini")
        return UploadedFile(uri, name)
    }

    private fun fileState(apiKey: String, name: String): String {
        val conn = URL("$BASE/v1beta/$name").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("x-goog-api-key", apiKey)
            val text = readResponse(conn)
            return JSONObject(text).optString("state", "ACTIVE")
        } finally {
            conn.disconnect()
        }
    }

    // ---------- HTTP helpers ----------

    private fun post(url: String, apiKey: String, body: JSONObject): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 480000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            return readResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) throw GeminiException(code, errorMessage(code, text))
        return text
    }

    private fun errorMessage(code: Int, body: String): String {
        val msg = try {
            JSONObject(body).optJSONObject("error")?.optString("message")
        } catch (_: Exception) {
            null
        }
        return "HTTP $code" + (if (!msg.isNullOrBlank()) ": $msg" else "")
    }

    // ---------- Request bodies ----------

    private fun interactionsBody(model: String, parts: List<Part>): JSONObject {
        val input = JSONArray()
        for (p in parts) {
            val o = JSONObject()
            when (p) {
                is Part.Text -> {
                    o.put("type", "text")
                    o.put("text", p.text)
                }
                is Part.Inline -> {
                    o.put("type", "audio")
                    o.put("data", Base64.encodeToString(p.bytes, Base64.NO_WRAP))
                    o.put("mime_type", p.mime)
                }
                is Part.FileRef -> {
                    o.put("type", p.kind)
                    o.put("uri", p.uri)
                    o.put("mime_type", p.mime)
                }
            }
            input.put(o)
        }
        return JSONObject().put("model", model).put("input", input)
    }

    private fun legacyBody(parts: List<Part>): JSONObject {
        val arr = JSONArray()
        for (p in parts) {
            val o = JSONObject()
            when (p) {
                is Part.Text -> o.put("text", p.text)
                is Part.Inline -> o.put(
                    "inline_data",
                    JSONObject().put("mime_type", p.mime)
                        .put("data", Base64.encodeToString(p.bytes, Base64.NO_WRAP))
                )
                is Part.FileRef -> o.put(
                    "file_data",
                    JSONObject().put("mime_type", p.mime).put("file_uri", p.uri)
                )
            }
            arr.put(o)
        }
        return JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", arr)))
    }

    // ---------- Response parsing (tolerant to both API shapes) ----------

    private fun extractText(json: String): String {
        val root = JSONObject(json)
        val sb = StringBuilder()

        // Interactions: steps[] -> model_output -> content[] -> text
        val steps = root.optJSONArray("steps")
        if (steps != null) {
            for (i in 0 until steps.length()) {
                val s = steps.optJSONObject(i) ?: continue
                if (s.optString("type") != "model_output") continue
                val content = s.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val c = content.optJSONObject(j) ?: continue
                    val t = c.optString("text", "")
                    if (t.isNotEmpty()) sb.append(t)
                }
            }
        }

        // Older generateContent: candidates[0].content.parts[].text
        if (sb.isEmpty()) {
            val parts = root.optJSONArray("candidates")
                ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            if (parts != null) {
                for (j in 0 until parts.length()) {
                    val t = parts.optJSONObject(j)?.optString("text", "") ?: ""
                    if (t.isNotEmpty()) sb.append(t)
                }
            }
        }

        // Possible alternative shape: outputs[].text
        if (sb.isEmpty()) {
            val outs = root.optJSONArray("outputs")
            if (outs != null) {
                for (j in 0 until outs.length()) {
                    val t = outs.optJSONObject(j)?.optString("text", "") ?: ""
                    if (t.isNotEmpty()) sb.append(t)
                }
            }
        }
        return sb.toString().trim()
    }
}
