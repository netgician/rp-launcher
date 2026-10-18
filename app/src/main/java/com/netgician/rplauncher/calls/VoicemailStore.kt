package com.netgician.rplauncher.calls

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.VoicemailContract
import com.netgician.rplauncher.ui.Demo

/**
 * Voicemails the phone keeps (visual voicemail): who, when, how long, any transcript, and the
 * recording. Android lets the phone app read them, so this works while RP Launcher is the phone
 * app. Whether any are here depends on the carrier: some only keep voicemail on their own line,
 * which Call voicemail reaches.
 */
object VoicemailStore {
    private val URI: Uri = VoicemailContract.Voicemails.CONTENT_URI

    fun list(context: Context, limit: Int = 60): List<Voicemail> {
        if (Demo.on) return Demo.voicemails
        if (!PhoneRole.isDefault(context)) return emptyList()
        val out = mutableListOf<Voicemail>()
        runCatching {
            context.contentResolver.query(
                URI,
                arrayOf(
                    VoicemailContract.Voicemails._ID,
                    VoicemailContract.Voicemails.NUMBER,
                    VoicemailContract.Voicemails.DATE,
                    VoicemailContract.Voicemails.DURATION,
                    VoicemailContract.Voicemails.IS_READ,
                    VoicemailContract.Voicemails.TRANSCRIPTION,
                    VoicemailContract.Voicemails.HAS_CONTENT,
                    VoicemailContract.Voicemails.SOURCE_PACKAGE,
                ),
                "${VoicemailContract.Voicemails.DELETED} = 0", null, "${VoicemailContract.Voicemails.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    out += Voicemail(
                        id = c.getLong(0),
                        number = c.getString(1).orEmpty(),
                        date = c.getLong(2),
                        durationSeconds = c.getLong(3),
                        isNew = c.getInt(4) == 0,
                        transcript = c.getString(5)?.trim()?.takeIf { it.isNotEmpty() },
                        hasAudio = c.getInt(6) != 0,
                        source = c.getString(7),
                    )
                }
            }
        }
        return out
    }

    fun audio(id: Long): Uri = ContentUris.withAppendedId(URI, id)

    fun markHeard(context: Context, v: Voicemail) {
        if (Demo.on || !v.isNew) return
        runCatching {
            context.contentResolver.update(audio(v.id), ContentValues().apply { put(VoicemailContract.Voicemails.IS_READ, 1) }, null, null)
        }
    }

    /** Asks the app that keeps it to download the recording, when it isn't on the phone yet. */
    fun fetch(context: Context, v: Voicemail) {
        if (Demo.on || v.hasAudio || v.source.isNullOrBlank()) return
        runCatching { context.sendBroadcast(Intent(VoicemailContract.ACTION_FETCH_VOICEMAIL, audio(v.id)).setPackage(v.source)) }
    }
}
