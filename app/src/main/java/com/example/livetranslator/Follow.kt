package com.example.livetranslator

import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Used while recording a video for the first time: writes a WAV file (to send to Gemini),
 * builds the reference fingerprint, and notes where speech starts after a pause.
 */
class SessionRecorder(file: File) : AudioSink {
    private val wav = WavRecorder(file)
    private val hashes = LongBuilder()
    private val fp = Fingerprinter { h, frame -> hashes.add(FpMatcher.pack(h, frame)) }
    private val onsetList = ArrayList<Double>()
    private var samples = 0L
    private var silentBlocks = 99

    val durationSec: Double
        get() = samples / AudioUtil.SAMPLE_RATE.toDouble()

    override fun onAudio(buf: ShortArray, n: Int) {
        wav.onAudio(buf, n)
        fp.feed(buf, n)

        var sum = 0.0
        for (i in 0 until n) {
            val v = buf[i].toDouble()
            sum += v * v
        }
        val rms = Math.sqrt(sum / n)
        if (rms > 300.0) {
            if (silentBlocks >= 4) onsetList.add(samples / AudioUtil.SAMPLE_RATE.toDouble())
            silentBlocks = 0
        } else {
            silentBlocks++
        }
        samples += n
    }

    override fun close() {
        wav.close()
    }

    fun fingerprint(): LongArray = hashes.toSortedArray()
    fun onsets(): List<Double> = onsetList
}

class FollowSession(val id: String, val ref: LongArray, val segments: List<Segment>) {
    val speakers: List<String> = segments.map { it.speaker }.distinct()
}

/**
 * Replay mode: listens to the live audio, fingerprints it, and every ~0.7 s looks for the best
 * match among the saved sessions. position() then tells where in the video we are.
 */
class FollowSink(private val sessions: List<FollowSession>) : AudioSink {

    companion object {
        private const val WINDOW_FRAMES = 250        // 4 seconds
        private const val MATCH_EVERY_MS = 700L
        private const val STALE_MS = 3500L
    }

    private val live = ArrayList<Long>()
    private val fp = Fingerprinter { h, frame ->
        synchronized(live) { live.add((frame.toLong() shl 32) or (h.toLong() and 0xFFFFFFFFL)) }
    }
    private val exec = Executors.newSingleThreadExecutor()
    private val matching = AtomicBoolean(false)
    private var lastMatchMs = 0L

    @Volatile private var lockSession: FollowSession? = null
    @Volatile private var lockDiff = 0
    @Volatile private var lastConfirmMs = 0L

    override fun onAudio(buf: ShortArray, n: Int) {
        fp.feed(buf, n)
        val now = SystemClock.elapsedRealtime()
        if (now - lastMatchMs < MATCH_EVERY_MS) return
        if (!matching.compareAndSet(false, true)) return
        lastMatchMs = now

        val curFrame = fp.frame
        val minFrame = curFrame - WINDOW_FRAMES
        var snapshot = LongArray(0)
        synchronized(live) {
            live.removeIf { (it ushr 32).toInt() < minFrame }
            snapshot = LongArray(live.size) { live[it] }
        }
        exec.execute {
            try {
                match(snapshot)
            } catch (_: Exception) {
            } finally {
                matching.set(false)
            }
        }
    }

    private fun match(snapshot: LongArray) {
        if (snapshot.size < 20) return
        var bestSession: FollowSession? = null
        var bestResult: FpMatcher.Result? = null
        for (s in sessions) {
            val r = FpMatcher.best(s.ref, snapshot) ?: continue
            val cur = bestResult
            if (cur == null || r.score > cur.score) {
                bestResult = r
                bestSession = s
            }
        }
        val r = bestResult ?: return
        val s = bestSession ?: return
        if (r.isReliable()) {
            lockSession = s
            lockDiff = r.diff
            lastConfirmMs = SystemClock.elapsedRealtime()
        }
    }

    /** Current position (seconds) inside the matched video, or null if there is no recent match. */
    fun position(): Pair<FollowSession, Double>? {
        val s = lockSession ?: return null
        if (SystemClock.elapsedRealtime() - lastConfirmMs > STALE_MS) return null
        val frames = fp.frame + lockDiff
        return Pair(s, frames * Fingerprinter.SEC_PER_FRAME)
    }

    override fun close() {
        exec.shutdownNow()
    }
}
