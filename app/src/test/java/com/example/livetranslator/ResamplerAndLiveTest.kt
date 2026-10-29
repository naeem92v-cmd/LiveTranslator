package com.example.livetranslator

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ResamplerAndLiveTest {

    private fun resample(src: Int, freq: Double, seconds: Int): ShortArray {
        val input = ShortArray(src * seconds) { (sin(2 * PI * freq * it / src) * 12000).toInt().toShort() }
        val out = ArrayList<Short>()
        val r = Resampler(src)
        var i = 0
        while (i < input.size) {
            val n = minOf(1000, input.size - i)
            r.process(input.copyOfRange(i, i + n), n) { b, c -> for (k in 0 until c) out.add(b[k]) }
            i += n
        }
        return out.toShortArray()
    }

    private fun zeroCrossings(s: ShortArray, skip: Int): Int {
        var c = 0
        for (i in skip + 1 until s.size) if ((s[i - 1] < 0) != (s[i] < 0)) c++
        return c
    }

    @Test
    fun downsamplesKeepingLengthAndPitch() {
        val out = resample(48000, 1000.0, 2)
        assertTrue("length ${out.size}", abs(out.size - 32000) <= 4)
        // 1 kHz = 2000 zero crossings per second
        val zc = zeroCrossings(out, 100)
        assertTrue("crossings $zc", abs(zc - 4000) <= 20)
    }

    @Test
    fun removesFrequenciesAboveTheNewBand() {
        // 14 kHz cannot exist at 16 kHz; without the low-pass it would fold back to 2 kHz
        val out = resample(44100, 14000.0, 1)
        var peak = 0
        for (i in 200 until out.size) peak = maxOf(peak, abs(out[i].toInt()))
        assertTrue("peak $peak", peak < 2400)
    }

    @Test
    fun sameRatePassesThrough() {
        val out = resample(16000, 500.0, 1)
        assertEquals(16000, out.size)
    }

    @Test
    fun liveSetupAsksForArabic() {
        val o = JSONObject(LiveMessages.setup("m1")).getJSONObject("setup")
        assertEquals("models/m1", o.getString("model"))
        val tc = o.getJSONObject("generationConfig").getJSONObject("translationConfig")
        assertEquals("ar", tc.getString("targetLanguageCode"))
    }

    @Test
    fun parsesLiveMessages() {
        assertTrue(LiveMessages.parse("{\"setupComplete\":{}}").setupComplete)
        val m = LiveMessages.parse(
            "{\"serverContent\":{\"inputTranscription\":{\"text\":\"Hello \"}," +
                "\"outputTranscription\":{\"text\":\"مرحباً\"},\"turnComplete\":true}}"
        )
        assertFalse(m.setupComplete)
        assertEquals("Hello ", m.sourceText)
        assertEquals("مرحباً", m.arabicText)
        assertTrue(m.turnComplete)
        assertTrue(LiveMessages.parse("{\"goAway\":{\"timeLeft\":\"5s\"}}").goAway)
    }
}
