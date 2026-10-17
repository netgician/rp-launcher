package com.netgician.rplauncher.ui

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
        state.say(if (practice) "Practice. Finding where you are." else "Finding where you are.")
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
                    "Practice done. Nothing was sent. In a real SOS, this text goes to ${People.join(names.ifEmpty { listOf("your SOS contacts") })}, " +
                        "and then you tap Call.",
                )
                contacts.isEmpty() -> say("No SOS contacts are set up, so no texts were sent. Tap Call $emergency for help.")
                TextRole.isDefault(context) -> {
                    val started = withContext(Dispatchers.IO) {
                        contacts.map { ct -> ct to runCatching { TextSender.send(context, listOf(ct.number), text) }.getOrNull() }
                    }
                    for ((ct, st) in started) {
                        if (st != null && st.ok) waiting[st.threadId] = ct.name
                        else say("Not sent to ${ct.name}. ${st?.problem ?: ""}")
                    }
                    say("Sending to ${People.join(names)}. Tap Call ${names.first()} to call.")
                }
                else -> {
                    val to = contacts.joinToString(";") { it.number }
                    state.start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$to")).putExtra("sms_body", text))
                    say("Your texting app is open with the SOS text ready. Press its Send button, then come back here to call.")
                }
            }
        }
    }

    // Each send's result, said aloud as it comes in.
    LaunchedEffect(Unit) {
        TextsBus.events.collect { e ->
            if (e is TextsBus.Event.Sent) {
                waiting.remove(e.threadId)?.let { name -> say(if (e.ok) "Sent to $name." else "Not sent to $name. ${e.words}") }
            }
        }
    }

    // The countdown: a buzz every second, the last three spoken. One tap on Cancel stops it.
    LaunchedEffect(Unit) {
        if (contacts.isEmpty() && !practice) {
            stage = SosStage.DONE
            say("No SOS contacts are set up yet. A helper can add them in Setup. Tap Call $emergency for help.")
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
                state.speaker.speak("SOS cancelled. Nothing was sent.", flush = false)
            }
        }
    }

    val cancel = {
        stage = SosStage.CANCELLED
        state.vibes.play(Landmarks.PAGE_TURN)
        state.say("SOS cancelled. Nothing was sent.")
    }

    InWindow(c.window) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (stage) {
                SosStage.COUNTDOWN -> {
                    SpokenText(
                        if (practice) "Practice SOS in $left" else "SOS in $left",
                        Modifier.fillMaxWidth().height(56.dp), state::say,
                        spoken = Sos.countdownWords(left, names, practice),
                    )
                    Tile("Cancel", Modifier.fillMaxWidth().weight(2f), spoken = "Cancel the SOS",
                        icon = TileIcon.Vector(Icons.Filled.Close), emphasized = true) { cancel() }
                    Tile("Send now", Modifier.fillMaxWidth().weight(1f), spoken = "Send the SOS now") { send() }
                }
                SosStage.LOCATING -> {
                    SpokenText("Finding where you are…", Modifier.fillMaxWidth().height(56.dp), state::say, maxLines = 2)
                    Tile("Cancel", Modifier.fillMaxWidth().weight(1f), spoken = "Cancel the SOS",
                        icon = TileIcon.Vector(Icons.Filled.Close), emphasized = true) { cancel() }
                }
                SosStage.DONE -> {
                    val first = contacts.firstOrNull()
                    if (first != null) {
                        Tile("Call ${first.name}", Modifier.fillMaxWidth().weight(2f), spoken = "Call ${first.name}",
                            icon = TileIcon.Vector(Icons.Filled.Call), emphasized = true) {
                            if (practice) state.say("In a real SOS, this calls ${first.name}.") else callNow(first.number)
                        }
                    }
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tile("Call $emergency", Modifier.weight(1f).fillMaxHeight(), spoken = "Call $emergency, emergency services",
                            icon = TileIcon.Vector(Icons.Filled.Call)) {
                            if (practice) state.say("In a real SOS, this opens the phone with $emergency ready to call.")
                            else state.start(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", emergency, null)))
                        }
                        Tile("Read again", Modifier.weight(1f).fillMaxHeight(), spoken = "Read what happened") {
                            state.say(lines.joinToString(" "))
                        }
                    }
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (contacts.isNotEmpty()) {
                            Tile("Send again", Modifier.weight(1f).fillMaxHeight(), spoken = "Send the SOS again with a new location") {
                                stage = SosStage.COUNTDOWN
                                left = 0
                                send()
                            }
                        }
                        Tile("Home", Modifier.weight(1f).fillMaxHeight(), icon = TileIcon.Vector(Icons.Filled.Home)) {
                            state.go(if (practice) Screen.SosSetup else Screen.Home)
                        }
                    }
                }
                SosStage.CANCELLED -> {
                    SpokenText("SOS cancelled", Modifier.fillMaxWidth().weight(1f), state::say, spoken = "SOS cancelled. Nothing was sent.")
                    Tile("Home", Modifier.fillMaxWidth().weight(1f), icon = TileIcon.Vector(Icons.Filled.Home), emphasized = true) {
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
    var shown by remember { mutableStateOf("Finding where you are…") }
    var busy by remember { mutableStateOf(false) }

    fun locate() {
        if (busy) return
        busy = true
        shown = "Finding where you are…"
        state.say("Finding where you are.")
        state.scope.launch {
            val p = withContext(Dispatchers.IO) { runCatching { Where.place(context) }.getOrNull() }
            place = p
            busy = false
            shown = when {
                p == null -> "Location not available"
                p.address != null -> p.address
                else -> "No street address here"
            }
            state.say(Sos.spokenPlace(p))
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Where.allowed(context)) locate()
        else {
            shown = "Location not allowed"
            state.say("Where am I needs location. A helper can allow it in the phone's settings.")
        }
    }
    LaunchedEffect(Unit) {
        when {
            !Where.isOn(context) -> {
                shown = "Location is off"
                state.say("Location is off on this phone. A helper can turn it on with Location settings.")
            }
            !Where.allowed(context) -> permission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
            else -> locate()
        }
    }
    val p = place
    val controls = buildList {
        add(Control("Again", spoken = "Find where I am again") {
            if (!Where.isOn(context)) state.say("Location is off on this phone.") else locate()
        })
        add(Control("Read again", icon = TileIcon.Vector(Icons.Filled.PlayArrow)) { state.say(Sos.spokenPlace(p)) })
        if (p != null) {
            add(Control("Text my location", spoken = "Send my location in a text") {
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
            add(Control("Location settings", spoken = "Open the phone's location settings") {
                state.start(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), "Could not open the setting")
            })
        }
        add(Control("Home", icon = TileIcon.Vector(Icons.Filled.Home)) { state.go(Screen.Home) })
    }
    ReaderLayout(
        state = state,
        body = shown,
        onBodyTap = { state.say(if (busy) "Still finding where you are" else Sos.spokenPlace(p)) },
        header = "Where am I",
        headerSpoken = "Where am I",
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
            contact == null -> state.say("No one picked")
            c.sosContacts.any { it.number == contact.number } -> state.say("${contact.name} is already an SOS contact")
            else -> {
                state.update { it.copy(sosContacts = it.sosContacts + contact) }
                state.say("${contact.name} added to SOS")
            }
        }
    }
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locationAllowed = Where.allowed(context)
        state.say(if (locationAllowed) "Location allowed" else "Location not allowed")
    }
    val explain = "SOS texts these people your location after a short countdown, which one tap cancels. " +
        "Then it shows a big button to call the first person. It never calls by itself." +
        if (!TextRole.isDefault(context)) " RP Launcher isn't the texting app, so SOS opens your texting app with the message ready, and you press Send." else ""
    LaunchedEffect(Unit) { state.say("SOS setup. $explain") }
    HelperColumn(state) {
        SettingRow("Back to setup") { state.go(Screen.Settings) }
        Words(state, explain)
        SettingRow("Add an SOS contact") {
            runCatching {
                pick.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
            }.onFailure { state.say("No contacts app found") }
        }
        c.sosContacts.forEachIndexed { i, ct ->
            SettingRow("Remove ${ct.name}", if (i == 0) "called first" else null) {
                state.update { it.copy(sosContacts = it.sosContacts.filterIndexed { n, _ -> n != i }) }
                state.say("${ct.name} removed from SOS")
            }
        }
        SettingRow("Countdown", "${c.sosSeconds} seconds") {
            val next = Sos.COUNTDOWN_CHOICES.after(c.sosSeconds)
            state.update { it.copy(sosSeconds = next) }
            state.say("$next second countdown")
        }
        SettingRow("Location", if (locationAllowed) "Allowed" else "Not allowed") {
            if (locationAllowed) state.say("Location is allowed")
            else location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        SettingRow("Practice SOS (sends nothing)") { state.go(Screen.Sos(practice = true)) }
        if (TileSpec.Sos !in c.tiles) {
            SettingRow("Put SOS on the home screen") {
                state.update { it.copy(tiles = it.tiles + TileSpec.Sos) }
                state.say("SOS added to the home screen")
            }
        }
        if (TileSpec.WhereAmI !in c.tiles) {
            SettingRow("Put Where am I on the home screen") {
                state.update { it.copy(tiles = it.tiles + TileSpec.WhereAmI) }
                state.say("Where am I added to the home screen")
            }
        }
    }
}
