package com.stt.floater

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.max

class WavRecorder {
    private val sampleRate = 16000
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var recording = false
    private val pcm = ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = max(minBuf, sampleRate * 2)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
        )
        check(r.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
        record = r
        pcm.reset()
        recording = true
        r.startRecording()
        thread = Thread {
            val chunk = ByteArray(bufSize)
            while (recording) {
                val read = r.read(chunk, 0, chunk.size)
                if (read > 0) pcm.write(chunk, 0, read)
            }
        }.also { it.start() }
    }

    fun stop(): ByteArray {
        recording = false
        try { thread?.join(500) } catch (_: Exception) {}
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        val pcmBytes = pcm.toByteArray()
        Log.d("WavRecorder", "captured ${pcmBytes.size} bytes of PCM")
        return toWav(pcmBytes, sampleRate)
    }

    private fun toWav(pcmData: ByteArray, sr: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sr * channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val totalSize = 36 + dataSize

        val out = ByteArrayOutputStream(44 + dataSize)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeIntLE(out, totalSize)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeIntLE(out, 16)
        writeShortLE(out, 1)
        writeShortLE(out, channels)
        writeIntLE(out, sr)
        writeIntLE(out, byteRate)
        writeShortLE(out, channels * bitsPerSample / 8)
        writeShortLE(out, bitsPerSample)
        out.write("data".toByteArray(Charsets.US_ASCII))
        writeIntLE(out, dataSize)
        out.write(pcmData)
        return out.toByteArray()
    }

    private fun writeIntLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xff)
        out.write((v shr 8) and 0xff)
        out.write((v shr 16) and 0xff)
        out.write((v shr 24) and 0xff)
    }

    private fun writeShortLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xff)
        out.write((v shr 8) and 0xff)
    }
}
