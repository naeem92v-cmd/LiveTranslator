package com.example.livetranslator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** One spoken line: time range (seconds from the start of the recording), speaker, English and Arabic. */
class Segment(
    val start: Double,
    val end: Double,
    val speaker: String,
    val en: String,
    val ar: String
)

class Session(
    val id: String,
    val title: String,
    val durationSec: Double,
    val segments: List<Segment>
) {
    val speakers: List<String> = segments.map { it.speaker }.distinct()
}

object SegmentTools {

    const val PROMPT =
        "Listen to this recording and split the speech into natural segments " +
            "(one sentence or short phrase each, at most about 12 seconds). " +
            "For every segment return: " +
            "\"start\" and \"end\" = times in seconds from the very beginning of the audio (numbers), " +
            "\"speaker\" = a label for who is speaking (use \"متحدث 1\", \"متحدث 2\", ... consistently for each " +
            "distinct voice; if a speaker says their own name or is clearly introduced by name, use that name), " +
            "\"en\" = what was said in the original language, " +
            "\"ar\" = a faithful, natural Arabic translation. " +
            "Return ONLY a JSON array with no markdown and no commentary, for example: " +
            "[{\"start\":0.0,\"end\":3.2,\"speaker\":\"متحدث 1\",\"en\":\"Hello everyone\",\"ar\":\"مرحباً بالجميع\"}]. " +
            "If there is no speech at all, return []."

    fun parse(raw: String): List<Segment> {
        val a = raw.indexOf('[')
        val b = raw.lastIndexOf(']')
        if (a < 0 || b <= a) throw GeminiException(0, "رد Gemini ليس بالصيغة المتوقعة")
        val arr = JSONArray(raw.substring(a, b + 1))
        val out = ArrayList<Segment>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val s = parseTime(o.opt("start"))
            val e = parseTime(o.opt("end"))
            val ar = o.optString("ar", "").trim()
            val en = o.optString("en", "").trim()
            if (ar.isEmpty() && en.isEmpty()) continue
            out.add(Segment(s, if (e > s) e else s + 3.0, o.optString("speaker", "").trim(), en, ar))
        }
        out.sortBy { it.start }
        return out
    }

    private fun parseTime(v: Any?): Double {
        if (v is Number) return v.toDouble()
        if (v is String) {
            val p = v.trim().split(":")
            return try {
                when (p.size) {
                    1 -> p[0].toDouble()
                    2 -> p[0].toDouble() * 60 + p[1].toDouble()
                    3 -> p[0].toDouble() * 3600 + p[1].toDouble() * 60 + p[2].toDouble()
                    else -> 0.0
                }
            } catch (_: Exception) {
                0.0
            }
        }
        return 0.0
    }

    /**
     * The model's timestamps can be off by a second or two. The app knows where speech really
     * starts after a pause (onsets), so move each segment start to the nearest unused onset
     * within 1.5 s.
     */
    fun snap(segs: List<Segment>, onsets: List<Double>): List<Segment> {
        if (onsets.isEmpty()) return segs
        val used = BooleanArray(onsets.size)
        val out = ArrayList<Segment>()
        for (s in segs) {
            var bi = -1
            var bd = 1.5
            for (i in onsets.indices) {
                if (used[i]) continue
                val d = abs(onsets[i] - s.start)
                if (d < bd) {
                    bd = d
                    bi = i
                }
            }
            if (bi >= 0) {
                used[bi] = true
                val ns = onsets[bi]
                val ne = maxOf(s.end + (ns - s.start), ns + 0.8)
                out.add(Segment(ns, ne, s.speaker, s.en, s.ar))
            } else {
                out.add(s)
            }
        }
        return out
    }

    /**
     * Gemini's reply has a length limit, so a long recording is translated in parts of about
     * [chunkSec]. Each cut is put just before a speech onset (after a pause) near the boundary,
     * so no sentence is split; without a nearby onset the cut is made at the boundary itself.
     */
    fun chooseCuts(
        durationSec: Double,
        onsets: List<Double>,
        chunkSec: Double = 600.0,
        slack: Double = 60.0
    ): List<Double> {
        val cuts = ArrayList<Double>()
        var last = 0.0
        while (durationSec - last > chunkSec + slack) {
            val target = last + chunkSec
            val near = onsets
                .filter { it > target - slack && it < target + slack }
                .minByOrNull { abs(it - target) }
            val cut = if (near != null) maxOf(near - 0.2, last + 1.0) else target
            cuts.add(cut)
            last = cut
        }
        return cuts
    }

    fun shift(segs: List<Segment>, offset: Double): List<Segment> =
        segs.map { Segment(it.start + offset, it.end + offset, it.speaker, it.en, it.ar) }

    private fun srtTime(t: Double): String {
        val ms = (t * 1000).toLong().coerceAtLeast(0L)
        return String.format(
            Locale.US, "%02d:%02d:%02d,%03d",
            ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000
        )
    }

    /** Standard .srt subtitle file: speaker + Arabic on the first line, original text below. */
    fun toSrt(segs: List<Segment>): String {
        val sb = StringBuilder()
        for ((i, s) in segs.withIndex()) {
            val nextStart = if (i + 1 < segs.size) segs[i + 1].start else Double.MAX_VALUE
            var end = s.end
            if (nextStart > s.start && end > nextStart - 0.05) end = nextStart - 0.05
            if (end <= s.start) end = s.start + 0.5
            sb.append(i + 1).append('\n')
            sb.append(srtTime(s.start)).append(" --> ").append(srtTime(end)).append('\n')
            if (s.speaker.isNotBlank()) sb.append(s.speaker).append(": ")
            sb.append(s.ar).append('\n')
            if (s.en.isNotBlank()) sb.append(s.en).append('\n')
            sb.append('\n')
        }
        return sb.toString()
    }

    fun formatText(segs: List<Segment>): String {
        val sb = StringBuilder()
        for (s in segs) {
            val sec = s.start.toInt()
            sb.append(String.format(Locale.US, "[%02d:%02d] ", sec / 60, sec % 60))
            if (s.speaker.isNotBlank()) sb.append(s.speaker).append(": ")
            sb.append(s.ar).append('\n')
            if (s.en.isNotBlank()) sb.append("      ").append(s.en).append('\n')
            sb.append('\n')
        }
        return sb.toString().trim()
    }
}

object SessionStore {

    private fun dir(ctx: Context): File = File(ctx.filesDir, "sessions").also { it.mkdirs() }

    fun save(ctx: Context, segs: List<Segment>, durationSec: Double, fp: LongArray, name: String? = null): String {
        val id = System.currentTimeMillis().toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val first = name?.take(40) ?: segs.firstOrNull { it.en.isNotBlank() }?.en?.take(28) ?: ""
        val title = if (first.isEmpty()) stamp else "$stamp · $first"

        writeJson(ctx, id, title, durationSec, segs)

        val bb = ByteBuffer.allocate(fp.size * 8)
        bb.asLongBuffer().put(fp)
        File(dir(ctx), "$id.fp").outputStream().use { it.write(bb.array()) }
        return id
    }

    /** Saves edits (title or lines) of an existing session; its fingerprint is kept. */
    fun update(ctx: Context, s: Session) {
        writeJson(ctx, s.id, s.title, s.durationSec, s.segments)
    }

    private fun writeJson(ctx: Context, id: String, title: String, durationSec: Double, segs: List<Segment>) {
        val arr = JSONArray()
        for (s in segs) {
            arr.put(
                JSONObject()
                    .put("s", s.start).put("e", s.end)
                    .put("sp", s.speaker).put("en", s.en).put("ar", s.ar)
            )
        }
        val root = JSONObject()
            .put("id", id).put("title", title).put("duration", durationSec).put("segments", arr)
        File(dir(ctx), "$id.json").writeText(root.toString())
    }

    fun list(ctx: Context): List<Session> {
        val files = dir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        val out = ArrayList<Session>()
        for (f in files) {
            try {
                out.add(parse(f.readText()))
            } catch (_: Exception) {
            }
        }
        out.sortByDescending { it.id.toLongOrNull() ?: 0L }
        return out
    }

    fun get(ctx: Context, id: String): Session? {
        val f = File(dir(ctx), "$id.json")
        if (!f.exists()) return null
        return try {
            parse(f.readText())
        } catch (_: Exception) {
            null
        }
    }

    fun loadFp(ctx: Context, id: String): LongArray {
        val bytes = File(dir(ctx), "$id.fp").readBytes()
        val lb = ByteBuffer.wrap(bytes).asLongBuffer()
        val a = LongArray(lb.remaining())
        lb.get(a)
        return a
    }

    fun delete(ctx: Context, id: String) {
        File(dir(ctx), "$id.json").delete()
        File(dir(ctx), "$id.fp").delete()
    }

    private fun parse(text: String): Session {
        val o = JSONObject(text)
        val arr = o.getJSONArray("segments")
        val segs = ArrayList<Segment>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            segs.add(
                Segment(
                    s.getDouble("s"), s.getDouble("e"),
                    s.optString("sp", ""), s.optString("en", ""), s.optString("ar", "")
                )
            )
        }
        return Session(o.getString("id"), o.getString("title"), o.optDouble("duration", 0.0), segs)
    }
}
