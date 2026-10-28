package com.example.livetranslator

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Anything that consumes the captured 16 kHz mono PCM16 audio. */
interface AudioSink {
    fun onAudio(buf: ShortArray, n: Int)
    fun close()
}

object AudioUtil {
    const val SAMPLE_RATE = 16000

    fun wavHeader(dataLen: Int): ByteArray {
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(dataLen + 36)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1.toShort())            // PCM
        bb.putShort(1.toShort())            // mono
        bb.putInt(SAMPLE_RATE)
        bb.putInt(SAMPLE_RATE * 2)          // byte rate
        bb.putShort(2.toShort())            // block align
        bb.putShort(16.toShort())           // bits per sample
        bb.put("data".toByteArray())
        bb.putInt(dataLen)
        return bb.array()
    }

    fun shortsToWav(s: ShortArray, len: Int): ByteArray {
        val data = ByteBuffer.allocate(len * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until len) data.putShort(s[i])
        return wavHeader(len * 2) + data.array()
    }

    /**
     * Copies the AAC audio track of a video/audio file into an .m4a file without re-encoding.
     * Returns false if the file has no AAC audio track (caller then uploads the original file).
     */
    fun extractAudio(ctx: Context, uri: Uri, out: File): Boolean {
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
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
            if (fmt.getString(MediaFormat.KEY_MIME) != "audio/mp4a-latm") return false

            ex.selectTrack(idx)
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val track = muxer.addTrack(fmt)
            muxer.start()
            started = true

            val buf = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                info.offset = 0
                info.size = n
                info.presentationTimeUs = ex.sampleTime
                info.flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(track, buf, info)
                ex.advance()
            }
            return out.length() > 0
        } catch (e: Exception) {
            return false
        } finally {
            try {
                if (started) muxer?.stop()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            ex.release()
        }
    }
}

/** Writes captured audio straight into a WAV file (header is fixed up on close). */
class WavRecorder(private val file: File) : AudioSink {
    private val raf = RandomAccessFile(file, "rw")
    private val tmp = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
    private var bytes = 0L

    init {
        raf.setLength(0)
        raf.write(ByteArray(44))
    }

    val durationSec: Long
        get() = bytes / (AudioUtil.SAMPLE_RATE * 2)

    override fun onAudio(buf: ShortArray, n: Int) {
        var i = 0
        while (i < n) {
            val chunk = minOf(n - i, 4096)
            tmp.clear()
            for (k in 0 until chunk) tmp.putShort(buf[i + k])
            raf.write(tmp.array(), 0, chunk * 2)
            bytes += chunk * 2
            i += chunk
        }
    }

    override fun close() {
        try {
            raf.seek(0)
            raf.write(AudioUtil.wavHeader(bytes.toInt()))
            raf.close()
        } catch (_: Exception) {
        }
    }
}
