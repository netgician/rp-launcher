package com.netgician.rplauncher.system

import android.content.Context
import java.io.File

/** One recorded note: the sound file, when it was made, and how long it is. */
data class VoiceNote(val file: File, val at: Long, val seconds: Int)

/**
 * The Voice notes tile's recordings, kept privately in RP Launcher's own storage on the phone
 * ("note-<time>-<seconds>.m4a"). Nothing leaves the phone.
 */
object VoiceNotes {
    const val MAX_SECONDS = 300

    private fun dir(context: Context): File = File(context.filesDir, "notes").apply { mkdirs() }

    /** Where a recording goes while it's being made. */
    fun newFile(context: Context): File = File(dir(context), "recording.part").apply { delete() }

    /** Keeps a finished recording as a note. */
    fun keep(context: Context, recording: File, seconds: Int): File {
        val dest = File(dir(context), "note-${System.currentTimeMillis()}-$seconds.m4a")
        if (!recording.renameTo(dest)) {
            recording.copyTo(dest, overwrite = true)
            recording.delete()
        }
        return dest
    }

    /** Newest first. */
    fun list(context: Context): List<VoiceNote> =
        dir(context).listFiles().orEmpty().mapNotNull { f ->
            val parts = f.name.removeSuffix(".m4a").split('-')
            if (!f.name.startsWith("note-") || !f.name.endsWith(".m4a") || parts.size != 3) return@mapNotNull null
            val at = parts[1].toLongOrNull() ?: return@mapNotNull null
            VoiceNote(f, at, parts[2].toIntOrNull() ?: 0)
        }.sortedByDescending { it.at }

    fun delete(note: VoiceNote) {
        note.file.delete()
    }
}
