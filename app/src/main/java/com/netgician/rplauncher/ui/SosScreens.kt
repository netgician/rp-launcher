package com.netgician.rplauncher.ui

import com.netgician.rplauncher.i18n.tr
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.netgician.rplauncher.model.Landmarks
import com.netgician.rplauncher.model.Place
import com.netgician.rplauncher.model.Sos
import com.netgician.rplauncher.model.TileSpec
import com.netgician.rplauncher.model.after
import com.netgician.rplauncher.system.Where
import com.netgician.rplauncher.texts.People
import com.netgician.rplauncher.texts.TextRole
import com.netgician.rplauncher.texts.TextSender
import com.netgician.rplauncher.texts.TextsBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale

private enum class SosStage { COUNTDOWN, LOCATING, DONE, CANCELLED }

private fun countryOf(context: Context): String =
    context.getSystemService(TelephonyManager::class.java)?.networkCountryIso?.takeIf { it.isNotBlank() }
        ?: Locale.getDefault().country

/**
 * SOS: a spoken, vibrating countdown that one tap cancels; then texts with the person's
 * location to their SOS contacts; then a big button to call the first one. It never calls by itself.
 */
@Composable
fun SosScreen(state: AppState, practice: Boolean) {
    val context = LocalContext.current
    val lv = LocalLv.current
    val c = state.config
    val contacts = c.sosContacts
    val names = contacts.map { it.name }
    var stage by remember { mutableStateOf(SosStage.COUNTDOWN) }
    var left by remember { mutableIntStateOf(c.sosSeconds) }
    var lines by remember { mutableStateOf(listOf<String>()) }
    val waiting = remember { mutableStateMapOf<Long, String>() }
    val emergency = remember { Sos.emergencyNumber(countryOf(context)) }

    fun say(line: String) {
        lines = lines + line
        state.speaker.speak(line, flush = false)
    }

    val callNow = { number: String ->
        val tel = Uri.fromParts("tel", number, null)
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        state.start(Intent(if (allowed) Intent.ACTION_CALL else Intent.ACTION_DIAL, tel))
    }

    fun send() {
        if (stage != SosStage.COUNTDOWN) return
        stage = SosStage.LOCATING
        state.say(if (practice) tr("Practice. Finding where you are.") else tr("Finding where you are."))
        // The activity's scope, not this screen's: leaving the screen must not stop an SOS that's sending.
        state.scope.launch {
            val place: Place? = withContext(Dispatchers.IO) { runCatching { Where.place(context) }.getOrNull() }
            if (stage == SosStage.CANCELLED) return@launch
            val time = DateFormat.getTimeFormat(context).format(Date(place?.fixTime ?: System.currentTimeMillis()))
            val text = Sos.message(place, time, Locale.getDefault().language)
            stage = SosStage.DONE
            lines = listOf(text)
            when {
                practice -> say(
                    tr("Practice done. Nothing was sent. In a real SOS, this text goes to %s, and then you tap Call.", People.join(names.ifEmpty { listOf(tr("your SOS contacts")) })),
                )
                contacts.isEmpty() -> say(tr("No SOS contacts are set up, so no texts were sent. Tap Call %s for help.", emergency))
                TextRole.isDefault(context) -> {
                    val started = withContext(Dispatchers.IO) {
                        contacts.map { ct -> ct to runCatching { TextSender.send(context, listOf(ct.number), text) }.getOrNull() }
                    }
                    for ((ct, st) in started) {
                        if (st != null && st.ok) waiting[st.threadId] = ct.name
                        else say(tr("Not sent to %s. %s", ct.name, st?.problem ?: ""))
                    }
                    say(tr("Sending to %s. Tap Call %s to call.", People.join(names), names.first()))
                }
                else -> {
                    val to = contacts.joinToString(";") { it.number }
                    state.start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$to")).putExtra("sms_body", text))
                    say(tr("Your texting app is open with the SOS text ready. Press its Send button, then come back here to call."))
                }
            }
        }
    }

    // Each send's result, said aloud as it comes in.
    LaunchedEffect(Unit) {
        TextsBus.events.collect { e ->
            if (e is TextsBus.Event.Sent) {
                waiting.remove(e.threadId)?.let { name -> say(if (e.ok) tr("Sent to %s.", name) else tr("Not sent to %s. %s", name, e.words)) }
            }
        }
    }

    // The countdown: a buzz every second, the last three spoken. One tap on Cancel stops it.
    LaunchedEffect(Unit) {
        if (contacts.isEmpty() && !practice) {
            stage = SosStage.DONE
            say(tr("No SOS contacts are set up yet. A helper can add them in Setup. Tap Call %s for help.", emergency))
            return@LaunchedEffect
        }
        state.say(Sos.countdownWords(c.sosSeconds, names, practice))
        delay(1_500)
        while (left > 0 && stage == SosStage.COUNTDOWN) {
            state.vibes.play(longArrayOf(0, 300))
            if (left <= 3) state.speaker.speak("$left", flush = false)
            delay(1_000)
            left--
        }
        if (stage == SosStage.COUNTDOWN) send()
    }
    DisposableEffect(Unit) {
        onDispose {
            if (stage == SosStage.COUNTDOWN) {
                stage = SosStage.CANCELLED
                state.speaker.speak(tr("SOS cancelled. Nothing was sent."), flush = false)
            }
        }
    }

    val cancel = {
        stage = SosStage.CANCELLED
        state.vibes.play(Landmarks.PAGE_TURN)
        state.say(tr("SOS cancelled. Nothing was sent."))
    }

    InWindow(c.window) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (stage) {
                SosStage.COUNTDOWN -> {
                    SpokenText(
                        if (practice) tr("Practice SOS in %s", left) else tr("SOS in %s", left),
                        Modifier.fillMaxWidth().height(56.dp), state::say,
                        spoken = Sos.countdownWords(left, names, practice),
                    )
                    Tile(tr("Cancel"), Modifier.fillMaxWidth().weight(2f), spoken = tr("Cancel the SOS"),
                        icon = TileIcon.Vector(Icons.Filled.Close), emphasized = true) { cancel() }
                    Tile(tr("Send now"), Modifier.fillMaxWidth().weight(1f), spoken = tr("Send the SOS now")) { send() }
                }
                SosStage.LOCATING -> {
                    SpokenText(tr("Finding where you are…"), Modifier.fillMaxWidth().height(56.dp), state::say, maxLines = 2)
                    Tile(tr("Cancel"), Modifier.fillMaxWidth().weight(1f), spoken = tr("Cancel the SOS"),
                        icon = TileIcon.Vector(Icons.Filled.Close), emphasized = true) { cancel() }
                }
                SosStage.DONE -> {
                    val first = contacts.firstOrNull()
                    if (first != null) {
                        Tile(tr("Call %s", first.name), Modifier.fillMaxWidth().weight(2f), spoken = tr("Call %s", first.name),
                            icon = TileIcon.Vector(Icons.Filled.Call), emphasized = true) {
                            if (practice) state.say(tr("In a real SOS, this calls %s.", first.name)) else callNow(first.number)
                        }
                    }
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tile(tr("Call %s", emergency), Modifier.weight(1f).fillMaxHeight(), spoken = tr("Call %s, emergency services", emergency),
                            icon = TileIcon.Vector(Icons.Filled.Call)) {
                            if (practice) state.say(tr("In a real SOS, this opens the phone with %s ready to call.", emergency))
                            else state.start(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", emergency, null)))
                        }
                        Tile(tr("Read again"), Modifier.weight(1f).fillMaxHeight(), spoken = tr("Read what happened")) {
                            state.say(lines.joinToString(" "))
                        }
                    }
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (contacts.isNotEmpty()) {
                            Tile(tr("Send again"), Modifier.weight(1f).fillMaxHeight(), spoken = tr("Send the SOS again with a new location")) {
                                stage = SosStage.COUNTDOWN
                                left = 0
                                send()
                            }
                        }
                        Tile(tr("Home"), Modifier.weight(1f).fillMaxHeight(), icon = TileIcon.Vector(Icons.Filled.Home)) {
                            state.go(if (practice) Screen.SosSetup else Screen.Home)
                        }
                    }
                }
                SosStage.CANCELLED -> {
                    SpokenText(tr("SOS cancelled"), Modifier.fillMaxWidth().weight(1f), state::say, spoken = tr("SOS cancelled. Nothing was sent."))
                    Tile(tr("Home"), Modifier.fillMaxWidth().weight(1f), icon = TileIcon.Vector(Icons.Filled.Home), emphasized = true) {
                        state.go(if (practice) Screen.SosSetup else Screen.Home)
                    }
                }
            }
        }
    }
}

/** "Where am I": the street address, read aloud, with how sure the phone is. */
@Composable
fun WhereAmIScreen(state: AppState) {
    val context = LocalContext.current
    var place by remember { mutableStateOf<Place?>(null) }
    var shown by remember { mutableStateOf(tr("Finding where you are…")) }
    var busy by remember { mutableStateOf(false) }

    fun locate() {
        if (busy) return
        busy = true
        shown = tr("Finding where you are…")
        state.say(tr("Finding where you are."))
        state.scope.launch {
            val p = withContext(Dispatchers.IO) { runCatching { Where.place(context) }.getOrNull() }
            place = p
            busy = false
            shown = when {
                p == null -> tr("Location not available")
                p.address != null -> p.address
                else -> tr("No street address here")
            }
            state.say(Sos.spokenPlace(p))
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Where.allowed(context)) locate()
        else {
            shown = tr("Location not allowed")
            state.say(tr("Where am I needs location. A helper can allow it in the phone's settings."))
        }
    }
    LaunchedEffect(Unit) {
        when {
            !Where.isOn(context) -> {
                shown = tr("Location is off")
                state.say(tr("Location is off on this phone. A helper can turn it on with Location settings."))
            }
            !Where.allowed(context) -> permission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
            else -> locate()
        }
    }
    val p = place
    val controls = buildList {
        add(Control(tr("Again"), spoken = tr("Find where I am again")) {
            if (!Where.isOn(context)) state.say(tr("Location is off on this phone.")) else locate()
        })
        add(Control(tr("Read again"), icon = TileIcon.Vector(Icons.Filled.PlayArrow)) { state.say(Sos.spokenPlace(p)) })
        if (p != null) {
            add(Control(tr("Text my location"), spoken = tr("Send my location in a text")) {
                val words = Sos.shareText(p)
                if (state.texts.isDefault) {
                    state.texts.draft = Draft(to = emptyList(), text = words)
                    state.texts.addingPerson = true
                    state.go(Screen.NewText)
                } else {
                    state.start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).putExtra("sms_body", words))
                }
            })
        }
        if (!Where.isOn(context)) {
            add(Control(tr("Location settings"), spoken = tr("Open the phone's location settings")) {
                state.start(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), tr("Could not open the setting"))
            })
        }
        add(Control(tr("Home"), icon = TileIcon.Vector(Icons.Filled.Home)) { state.go(Screen.Home) })
    }
    ReaderLayout(
        state = state,
        body = shown,
        onBodyTap = { state.say(if (busy) tr("Still finding where you are") else Sos.spokenPlace(p)) },
        header = tr("Where am I"),
        headerSpoken = tr("Where am I"),
        controls = controls,
    ) { ctl, m, pos -> ControlTile(state, ctl, m, pos) }
}

/** Setup for SOS: who it texts, the countdown, location, and a practice run that sends nothing. */
@Composable
fun SosSetupScreen(state: AppState) {
    val context = LocalContext.current
    val c = state.config
    var locationAllowed by remember { mutableStateOf(Where.allowed(context)) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val contact = r.data?.data?.let { readPhoneContact(context, it) }
        when {
            contact == null -> state.say(tr("No one picked"))
            c.sosContacts.any { it.number == contact.number } -> state.say(tr("%s is already an SOS contact", contact.name))
            else -> {
                state.update { it.copy(sosContacts = it.sosContacts + contact) }
                state.say(tr("%s added to SOS", contact.name))
            }
        }
    }
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locationAllowed = Where.allowed(context)
        state.say(if (locationAllowed) tr("Location allowed") else tr("Location not allowed"))
    }
    val explain = tr("SOS texts these people your location after a short countdown, which one tap cancels. Then it shows a big button to call the first person. It never calls by itself.") +
        if (!TextRole.isDefault(context)) tr(" RP Launcher isn't the texting app, so SOS opens your texting app with the message ready, and you press Send.") else ""
    LaunchedEffect(Unit) { state.say(tr("SOS setup. %s", explain)) }
    HelperColumn(state) {
        SettingRow(tr("Back to setup")) { state.go(Screen.Settings) }
        Words(state, explain)
        SettingRow(tr("Add an SOS contact")) {
            runCatching {
                pick.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
            }.onFailure { state.say(tr("No contacts app found")) }
        }
        c.sosContacts.forEachIndexed { i, ct ->
            SettingRow(tr("Remove %s", ct.name), if (i == 0) tr("called first") else null) {
                state.update { it.copy(sosContacts = it.sosContacts.filterIndexed { n, _ -> n != i }) }
                state.say(tr("%s removed from SOS", ct.name))
            }
        }
        SettingRow(tr("Countdown"), tr("%s seconds", c.sosSeconds)) {
            val next = Sos.COUNTDOWN_CHOICES.after(c.sosSeconds)
            state.update { it.copy(sosSeconds = next) }
            state.say(tr("%s second countdown", next))
        }
        SettingRow(tr("Location"), if (locationAllowed) tr("Allowed") else tr("Not allowed")) {
            if (locationAllowed) state.say(tr("Location is allowed"))
            else location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        SettingRow(tr("Practice SOS (sends nothing)")) { state.go(Screen.Sos(practice = true)) }
        if (TileSpec.Sos !in c.tiles) {
            SettingRow(tr("Put SOS on the home screen")) {
                state.update { it.copy(tiles = it.tiles + TileSpec.Sos) }
                state.say(tr("SOS added to the home screen"))
            }
        }
        if (TileSpec.WhereAmI !in c.tiles) {
            SettingRow(tr("Put Where am I on the home screen")) {
                state.update { it.copy(tiles = it.tiles + TileSpec.WhereAmI) }
                state.say(tr("Where am I added to the home screen"))
            }
        }
    }
}
