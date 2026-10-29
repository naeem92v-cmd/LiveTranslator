package com.example.livetranslator

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Decodes the audio track of a video/audio file to 16 kHz mono PCM16, the same format the
 * live capture produces, so a file can be fingerprinted and followed like a recording.
 */
object AudioDecoder {

    /** Feeds the decoded audio to [out]. Returns false if the file has no decodable audio. */
    fun decode(ctx: Context, uri: Uri, out: (ShortArray, Int) -> Unit, onProgress: (Int) -> Unit): Boolean {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var idx = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    idx = i
                    break
                }
            }
            if (idx < 0) return false
            val fmt = ex.getTrackFormat(idx)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return false
            val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            ex.selectTrack(idx)

            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()

            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var resampler = Resampler(fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            var isFloat = false
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var lastPct = -1
            var produced = false
            var idle = 0

            while (true) {
                if (!inDone) {
                    val ii = c.dequeueInputBuffer(10000)
                    if (ii >= 0) {
                        val b = c.getInputBuffer(ii)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10000)
                if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // some decoders never flag end-of-stream: give up after ~3 s of nothing
                    if (inDone && ++idle > 300) break
                    continue
                }
                idle = 0
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    resampler = Resampler(of.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (oi >= 0) {
                    if (info.size > 0) {
                        val ob = c.getOutputBuffer(oi)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.order(ByteOrder.LITTLE_ENDIAN)
                        val mono: ShortArray
                        if (isFloat) {
                            val fb = ob.asFloatBuffer()
                            val frames = fb.remaining() / channels
                            mono = ShortArray(frames) { f ->
                                var sum = 0f
                                for (ch in 0 until channels) sum += fb.get(f * channels + ch)
                                (sum / channels * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                            }
                        } else {
                            val sb = ob.asShortBuffer()
                            val frames = sb.remaining() / channels
                            mono = ShortArray(frames) { f ->
                                var sum = 0
                                for (ch in 0 until channels) sum += sb.get(f * channels + ch)
                                (sum / channels).toShort()
                            }
                        }
                        if (mono.isNotEmpty()) {
                            resampler.process(mono, mono.size, out)
                            produced = true
                        }
                        if (durationUs > 0) {
                            val pct = (info.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                    c.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
            return produced
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            ex.release()
        }
    }
}

/**
 * Streaming resampler to 16 kHz: a windowed-sinc low-pass (so higher frequencies do not fold
 * back into the band the fingerprint uses) followed by linear interpolation.
 */
class Resampler(private val src: Int, private val dst: Int = AudioUtil.SAMPLE_RATE) {

    companion object {
        private const val TAPS = 31
    }

    private val step = src.toDouble() / dst
    private val kernel: FloatArray? = if (src > dst) lowPass(0.45 * dst / src) else null
    private val history = FloatArray(TAPS - 1)

    // read position, relative to the start of the current input block; -1 means the previous sample
    private var pos = 0.0
    private var prev = 0f

    private fun lowPass(cutoff: Double): FloatArray {
        val m = TAPS - 1
        val k = FloatArray(TAPS) { i ->
            val x = i - m / 2.0
            val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
            val w = 0.54 - 0.46 * cos(2 * PI * i / m)
            (sinc * w).toFloat()
        }
        val sum = k.sum()
        for (i in k.indices) k[i] /= sum
        return k
    }

    fun process(input: ShortArray, n: Int, out: (ShortArray, Int) -> Unit) {
        if (n <= 0) return
        if (src == dst) {
            out(input, n)
            return
        }
        val x = FloatArray(n)
        val k = kernel
        if (k == null) {
            for (i in 0 until n) x[i] = input[i].toFloat()
        } else {
            // filter with the tail of the previous block as history
            val h = history.size
            for (i in 0 until n) {
                var acc = 0f
                for (t in 0 until TAPS) {
                    val j = i - t
                    val v = if (j >= 0) input[j].toFloat() else history[h + j]
                    acc += k[t] * v
                }
                x[i] = acc
            }
            if (n >= h) {
                for (i in 0 until h) history[i] = input[n - h + i].toFloat()
            } else {
                System.arraycopy(history, n, history, 0, h - n)
                for (i in 0 until n) history[h - n + i] = input[i].toFloat()
            }
        }

        val o = ShortArray((n / step).toInt() + 2)
        var c = 0
        while (true) {
            val i = floor(pos).toInt()
            if (i + 1 >= n) break
            val frac = (pos - i).toFloat()
            val a = if (i < 0) prev else x[i]
            val b = x[i + 1]
            if (c < o.size) o[c++] = (a + (b - a) * frac).toInt().coerceIn(-32768, 32767).toShort()
            pos += step
        }
        prev = x[n - 1]
        pos -= n
        if (c > 0) out(o, c)
    }
}
