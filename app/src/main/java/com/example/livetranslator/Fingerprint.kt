package com.example.livetranslator

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Audio fingerprinting in the style of Shazam ("landmark hashing").
 *
 * The same code runs twice:
 *  - while recording a video the first time (to build a reference fingerprint), and
 *  - while the video is replayed (to find out where in the reference we currently are).
 *
 * Audio is 16 kHz mono. Every 16 ms a 512-sample frame is transformed with an FFT, the strongest
 * spectral peak in each of 5 frequency bands is kept, and pairs of peaks that are 8..63 frames
 * apart are hashed as (frequency1, frequency2, time difference). Each hash is reported together
 * with the frame number of its earlier peak.
 */
class Fingerprinter(private val onHash: (hash: Int, frame: Int) -> Unit) {

    companion object {
        const val N = 512
        const val HOP = 256
        const val SEC_PER_FRAME = 0.016          // HOP / 16000
        private const val LOG2N = 9
        private const val BINS = N / 2
        private const val MIN_BIN = 8
        private const val MIN_DT = 8
        private const val MAX_DT = 63            // must fit in 6 bits
        private const val FAN_OUT = 3
        private const val GATE_RMS = 0.002f      // frames quieter than this produce no peaks
        private val BAND_EDGES = intArrayOf(8, 16, 32, 64, 128, 256)
    }

    private class Peak(val frame: Int, val bin: Int, val pow: Float)

    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2.0 * PI * it / (N - 1))).toFloat() }
    private val cosT = FloatArray(N / 2) { cos(2.0 * PI * it / N).toFloat() }
    private val sinT = FloatArray(N / 2) { (-sin(2.0 * PI * it / N)).toFloat() }
    private val rev = IntArray(N)
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val power = FloatArray(BINS)

    private var data = FloatArray(N + 8192)
    private var len = 0
    private val recent = ArrayDeque<Peak>()

    /** Number of frames processed so far (also the frame index of the next frame). */
    @Volatile
    var frame = 0
        private set

    init {
        for (i in 0 until N) {
            var x = i
            var r = 0
            for (b in 0 until LOG2N) {
                r = (r shl 1) or (x and 1)
                x = x shr 1
            }
            rev[i] = r
        }
    }

    fun feed(s: ShortArray, n: Int) {
        if (len + n > data.size) data = data.copyOf(len + n + N)
        for (i in 0 until n) data[len + i] = s[i] / 32768f
        len += n
        var start = 0
        while (len - start >= N) {
            processFrame(start)
            start += HOP
        }
        if (start > 0) {
            System.arraycopy(data, start, data, 0, len - start)
            len -= start
        }
    }

    private fun processFrame(start: Int) {
        var energy = 0f
        for (i in 0 until N) {
            val v = data[start + i]
            energy += v * v
            re[rev[i]] = v * window[i]
            im[rev[i]] = 0f
        }
        val f = frame
        frame = f + 1

        // prune peaks that are too old to be paired
        while (recent.isNotEmpty() && f - recent.first().frame > MAX_DT) recent.removeFirst()

        val rms = Math.sqrt((energy / N).toDouble()).toFloat()
        if (rms < GATE_RMS) return

        fft()
        var mean = 0f
        for (b in 0 until BINS) {
            power[b] = re[b] * re[b] + im[b] * im[b]
            if (b >= MIN_BIN) mean += power[b]
        }
        mean /= (BINS - MIN_BIN)
        val thr = mean * 4f

        val newPeaks = ArrayList<Peak>(5)
        for (band in 0 until BAND_EDGES.size - 1) {
            var best = -1
            var bp = 0f
            for (b in BAND_EDGES[band] until BAND_EDGES[band + 1]) {
                if (power[b] > bp) {
                    bp = power[b]
                    best = b
                }
            }
            if (best >= 0 && bp > thr) newPeaks.add(Peak(f, best, bp))
        }

        for (p in newPeaks) {
            // the FAN_OUT strongest earlier peaks that are at least MIN_DT frames old
            val top = arrayOfNulls<Peak>(FAN_OUT)
            for (c in recent) {
                if (f - c.frame < MIN_DT) break
                var cand: Peak = c
                for (t in 0 until FAN_OUT) {
                    val cur = top[t]
                    if (cur == null) {
                        top[t] = cand
                        break
                    }
                    if (cand.pow > cur.pow) {
                        top[t] = cand
                        cand = cur
                    }
                }
            }
            for (a in top) {
                if (a == null) continue
                val dt = f - a.frame
                onHash((a.bin shl 14) or (p.bin shl 6) or dt, a.frame)
            }
        }
        recent.addAll(newPeaks)
    }

    private fun fft() {
        var size = 2
        while (size <= N) {
            val half = size / 2
            val step = N / size
            var i = 0
            while (i < N) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosT[k]
                    val wi = sinT[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr
                    im[j + half] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}

/** Growable LongArray. */
class LongBuilder {
    private var a = LongArray(1 shl 16)
    private var n = 0

    fun add(v: Long) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = v
    }

    fun toSortedArray(): LongArray {
        val r = a.copyOf(n)
        r.sort()
        return r
    }
}

object FpMatcher {
    private const val MAX_PER_HASH = 40

    /**
     * diff: reference frame minus live frame; score: votes for it (neighbours included);
     * second: best score of any offset more than 3 frames away (used to reject ambiguous matches).
     */
    class Result(val diff: Int, val score: Int, val second: Int) {
        /** Thresholds tuned on a simulation: true matches scored >= 320, wrong videos <= 131. */
        fun isReliable(): Boolean = score >= 200 && score >= 3 * (second + 1)
    }

    fun pack(hash: Int, frame: Int): Long = (hash.toLong() shl 32) or (frame.toLong() and 0xFFFFFFFFL)

    private fun lowerBound(a: LongArray, key: Long): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * ref: sorted array of pack(hash, referenceFrame).
     * live: array of pack(frame, hash) with the roles swapped: (liveFrame shl 32) or hash.
     * Returns the reference-minus-live frame offset with the most votes (neighbours included).
     */
    fun best(ref: LongArray, live: LongArray): Result? {
        val votes = HashMap<Int, Int>()
        for (x in live) {
            val tl = (x ushr 32).toInt()
            val h = x and 0xFFFFFFFFL
            var i = lowerBound(ref, h shl 32)
            var c = 0
            while (i < ref.size && (ref[i] ushr 32) == h && c < MAX_PER_HASH) {
                val tr = (ref[i] and 0xFFFFFFFFL).toInt()
                val d = tr - tl
                votes[d] = (votes[d] ?: 0) + 1
                i++
                c++
            }
        }
        val scores = HashMap<Int, Int>()
        for ((d, v) in votes) scores[d] = v + (votes[d - 1] ?: 0) + (votes[d + 1] ?: 0)
        var bestD = 0
        var bestS = 0
        for ((d, s) in scores) {
            if (s > bestS) {
                bestS = s
                bestD = d
            }
        }
        var second = 0
        for ((d, s) in scores) {
            if (abs(d - bestD) > 3 && s > second) second = s
        }
        return if (bestS > 0) Result(bestD, bestS, second) else null
    }
}
