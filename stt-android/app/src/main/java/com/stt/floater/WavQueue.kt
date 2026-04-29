package com.stt.floater

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Bounded on-disk FIFO for WAV blobs that failed to upload.
 *
 * On send failure, the audio + its routing metadata (tmux target, submit/paste
 * flags) are persisted to the app's private files dir. On next mic interaction
 * we attempt to drain the oldest entry first so the user's words don't get
 * silently lost to a transient network hiccup.
 *
 * Capacity is intentionally small (default 10). Audio is bulky and stale audio
 * is rarely worth keeping — better to drop oldest when full than fill storage.
 */
class WavQueue(context: Context, private val maxEntries: Int = 10) {

    private val dir: File = File(context.filesDir, "wav_queue").apply { mkdirs() }

    data class Meta(
        val tmuxTarget: String,
        val submit: Boolean,
        val pasteOnPc: Boolean,
        val timestampMs: Long,
    )

    data class Entry(val wavFile: File, val metaFile: File, val meta: Meta)

    /** Persist a WAV + its metadata. Drops the oldest entry if at capacity. */
    fun enqueue(wav: ByteArray, meta: Meta) {
        try {
            pruneTo(maxEntries - 1)
            val base = "${meta.timestampMs}_${UUID.randomUUID().toString().take(8)}"
            File(dir, "$base.wav").writeBytes(wav)
            File(dir, "$base.json").writeText(metaToJson(meta))
        } catch (e: Exception) {
            Log.w(TAG, "enqueue failed", e)
        }
    }

    /** Returns oldest queued entry (by filename timestamp prefix), or null. */
    fun peekOldest(): Entry? {
        val wavs = listWavs() ?: return null
        if (wavs.isEmpty()) return null
        val oldest = wavs.minByOrNull { it.name } ?: return null
        val meta = readMetaFor(oldest) ?: return null
        val metaFile = File(dir, oldest.nameWithoutExtension + ".json")
        return Entry(oldest, metaFile, meta)
    }

    /** Remove an entry after successful drain. */
    fun delete(entry: Entry) {
        runCatching { entry.wavFile.delete() }
        runCatching { entry.metaFile.delete() }
    }

    fun size(): Int = listWavs()?.size ?: 0

    fun isEmpty(): Boolean = size() == 0

    private fun listWavs(): List<File>? =
        dir.listFiles { f -> f.isFile && f.extension == "wav" }?.toList()

    private fun pruneTo(maxKeep: Int) {
        val wavs = listWavs() ?: return
        if (wavs.size <= maxKeep) return
        // Sort oldest first by name (timestamp prefix), drop excess.
        val toDrop = wavs.sortedBy { it.name }.take(wavs.size - maxKeep)
        for (w in toDrop) {
            runCatching { w.delete() }
            runCatching { File(dir, w.nameWithoutExtension + ".json").delete() }
        }
    }

    private fun metaToJson(m: Meta): String =
        JSONObject().apply {
            put("tmux_target", m.tmuxTarget)
            put("submit", m.submit)
            put("paste_on_pc", m.pasteOnPc)
            put("timestamp_ms", m.timestampMs)
        }.toString()

    private fun readMetaFor(wav: File): Meta? = try {
        val metaFile = File(dir, wav.nameWithoutExtension + ".json")
        if (!metaFile.exists()) {
            // Corrupt entry; clean up.
            wav.delete()
            null
        } else {
            val j = JSONObject(metaFile.readText())
            Meta(
                tmuxTarget = j.optString("tmux_target", ""),
                submit = j.optBoolean("submit", true),
                pasteOnPc = j.optBoolean("paste_on_pc", false),
                timestampMs = j.optLong("timestamp_ms", System.currentTimeMillis()),
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "readMeta failed for ${wav.name}", e)
        null
    }

    companion object {
        private const val TAG = "WavQueue"
    }
}
