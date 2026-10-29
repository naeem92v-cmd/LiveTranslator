package com.example.livetranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class FingerprintTest {

    /** Synthetic "music": a new chord of three random tones every 100 ms, plus a little noise. */
    private fun signal(seed: Int, seconds: Int): ShortArray {
        val r = Random(seed)
        val sr = AudioUtil.SAMPLE_RATE
        val out = ShortArray(sr * seconds)
        val note = sr / 10
        var i = 0
        while (i < out.size) {
            val f = DoubleArray(3) { 200.0 + r.nextDouble() * 5800.0 }
            for (k in 0 until note) {
                if (i + k >= out.size) break
                val t = (i + k).toDouble() / sr
                var v = 0.0
                for (x in f) v += 0.25 * sin(2 * PI * x * t)
                v += (r.nextDouble() - 0.5) * 0.02
                out[i + k] = (v * 32767).toInt().coerceIn(-32768, 32767).toShort()
            }
            i += note
        }
        return out
    }

    private fun refPrint(s: ShortArray): LongArray {
        val b = LongBuilder()
        val fp = Fingerprinter { h, frame -> b.add(FpMatcher.pack(h, frame)) }
        feedInBlocks(fp, s, 0, s.size)
        return b.toSortedArray()
    }

    private fun livePrint(s: ShortArray, from: Int, to: Int): LongArray {
        val live = ArrayList<Long>()
        val fp = Fingerprinter { h, frame -> live.add((frame.toLong() shl 32) or (h.toLong() and 0xFFFFFFFFL)) }
        feedInBlocks(fp, s, from, to)
        return live.toLongArray()
    }

    private fun feedInBlocks(fp: Fingerprinter, s: ShortArray, from: Int, to: Int) {
        var i = from
        val block = ShortArray(2048)
        while (i < to) {
            val n = minOf(block.size, to - i)
            System.arraycopy(s, i, block, 0, n)
            fp.feed(block, n)
            i += n
        }
    }

    @Test
    fun findsThePositionInsideTheReference() {
        val sr = AudioUtil.SAMPLE_RATE
        val ref = signal(42, 60)
        val refFp = refPrint(ref)
        // 20 s in: 320000 samples = exactly 1250 frames of 256 samples
        val live = livePrint(ref, 20 * sr, 25 * sr)
        val r = FpMatcher.best(refFp, live)
        assertNotNull(r)
        assertTrue("diff was ${r!!.diff}", kotlin.math.abs(r.diff - 1250) <= 2)
        assertTrue("score ${r.score} vs second ${r.second}", r.score > 3 * (r.second + 1))
    }

    @Test
    fun otherAudioScoresMuchLower() {
        val sr = AudioUtil.SAMPLE_RATE
        val ref = signal(42, 60)
        val refFp = refPrint(ref)
        val right = FpMatcher.best(refFp, livePrint(ref, 20 * sr, 25 * sr))!!
        val other = signal(7, 5)
        val wrong = FpMatcher.best(refFp, livePrint(other, 0, other.size))
        val wrongScore = wrong?.score ?: 0
        assertTrue("right ${right.score} wrong $wrongScore", wrongScore * 3 < right.score)
    }
}
