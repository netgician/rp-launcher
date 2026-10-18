package com.netgician.rplauncher.ui

import com.netgician.rplauncher.i18n.tr
import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.netgician.rplauncher.calls.CallLogStore
import com.netgician.rplauncher.calls.CallWords
import com.netgician.rplauncher.calls.PhoneRole
import com.netgician.rplauncher.calls.RecentCall
import com.netgician.rplauncher.calls.RpInCallService
import com.netgician.rplauncher.model.TileSpec
import com.netgician.rplauncher.texts.People
import com.netgician.rplauncher.texts.TextSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

/** Places a call from RP Launcher's own keypad or lists, asking for the phone permission once. */
@Composable
private fun rememberCaller(state: AppState): (String) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<String?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val n = pending
        pending = null
        if (ok && n != null) {
            if (!PhoneRole.call(context, n)) state.say(tr("The call couldn't be started."))
        } else if (n != null) {
            state.say(tr("Calls need the phone permission. Opening the phone's dialer instead."))
            state.start(Intent(Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", n, null)))
        }
    }
    return { number ->
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        if (Demo.isDemoNumber(number)) {
            com.netgician.rplauncher.calls.CallActivity.openDemo(context, incoming = false, name = state.texts.names.of(number), number = number)
        } else if (allowed) {
            if (!PhoneRole.call(context, number)) state.say(tr("The call couldn't be started."))
        } else {
            pending = number
            ask.launch(Manifest.permission.CALL_PHONE)
        }
    }
}

/** The Phone tile, when RP Launcher is the phone app. */
@Composable
fun PhoneHomeScreen(state: AppState) {
    val context = LocalContext.current
    var missed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        missed = CallLogStore.missedCount(context)
        onPauseOrDispose { }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val c = r.data?.data?.let { readPhoneContact(context, it) }
        if (c == null) state.say(tr("No one picked")) else state.go(Screen.ConfirmCall(c))
    }
    LaunchedEffect(Unit) { state.say(if (missed > 0) tr("Phone. %s missed calls.", missed) else tr("Phone")) }
    ChoiceList(
        state, tr("Phone"),
        choices = listOf(
            Control(tr("Home"), icon = TileIcon.Vector(Icons.Filled.Home)) { state.go(Screen.Home) },
            Control(tr("Keypad"), spoken = tr("Keypad, to type a number"), icon = TileIcon.Vector(Icons.Filled.Call)) { state.go(Screen.Dial("")) },
            Control(
                if (missed > 0) tr("Recent calls (%s)", missed) else tr("Recent calls"),
                spoken = if (missed > 0) tr("Recent calls, %s missed", missed) else tr("Recent calls"),
                emphasized = missed > 0,
            ) { state.go(Screen.Recents) },
            Control(tr("Call a contact"), icon = TileIcon.Vector(Icons.Filled.Person)) {
                runCatching { pick.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }
                    .onFailure { state.say(tr("No contacts app found")) }
            },
            Control(tr("Voicemail"), spoken = tr("Voicemail")) { state.go(Screen.Voicemail) },
        ),
    )
}

/** A big keypad that says each key. Call places the call; Delete fixes mistakes; long press 0 for +. */
@Composable
fun DialScreen(state: AppState, initial: String) {
    var number by remember(initial) { mutableStateOf(initial.filter { it.isDigit() || it in "+*#" }) }
    val call = rememberCaller(state)
    LaunchedEffect(initial) {
        state.say(if (number.isEmpty()) tr("Keypad. Type the number, then tap Call.") else tr("Keypad. %s. Tap Call to call.", People.spoken(number)))
    }
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#", "delete", "call", "back")
    InWindow(state.config.window) {
        PagedTiles(
            items = keys,
            page = 0,
            onPage = { _, _ -> },
            cols = 3,
            rows = 5,
            onSpeak = state::say,
            header = { m ->
                SpokenText(
                    if (number.isEmpty()) tr("Type a number") else CallWords.grouped(number), m, state::say,
                    spoken = if (number.isEmpty()) tr("Nothing typed yet") else People.spoken(number), maxSp = 30f,
                )
            },
            minTarget = 0f,
        ) { key, m, _ ->
            when (key) {
                "delete" -> Tile(tr("Delete"), m, spoken = tr("Delete the last digit")) {
                    if (number.isEmpty()) state.say(tr("Nothing to delete")) else {
                        number = number.dropLast(1)
                        state.say(tr("Deleted"))
                    }
                }
                "call" -> Tile(tr("Call"), m, spoken = tr("Call this number"), icon = TileIcon.Vector(Icons.Filled.Call), emphasized = true) {
                    if (number.length < 2) state.say(tr("Type the number first")) else {
                        state.say(tr("Calling %s", People.spoken(number)))
                        call(number)
                    }
                }
                "back" -> Tile(tr("Back"), m) { state.back() }
                "0" -> Tile("0", m, spoken = tr("0. Hold for plus"), onLongPress = {
                    number += "+"
                    state.say("plus")
                }) {
                    number += "0"
                    state.say("0")
                }
                else -> Tile(key, m, spoken = CallWords.key(key)) {
                    if (number.length < 20) {
                        number += key
                        state.say(CallWords.key(key))
                    }
                }
            }
        }
    }
}

/** Recent calls, newest first, read aloud; a tap offers Call or Text. */
@Composable
fun RecentsScreen(state: AppState) {
    val context = LocalContext.current
    val t = state.texts
    var list by remember { mutableStateOf<List<RecentCall>?>(null) }
    LaunchedEffect(Unit) {
        list = withContext(Dispatchers.IO) { CallLogStore.recent(context) }
        val missed = list.orEmpty().count { it.isNew }
        state.say(if (missed > 0) tr("Recent calls. %s missed.", missed) else tr("Recent calls"))
        withContext(Dispatchers.IO) { CallLogStore.markSeen(context) }
    }
    val time: (Long) -> String = { DateFormat.getTimeFormat(context).format(Date(it)) }
    val choices = buildList {
        add(Control(tr("Back"), spoken = tr("Back to Phone")) { state.go(Screen.PhoneHome) })
        list.orEmpty().forEach { r ->
            val who = r.name ?: if (r.number.isBlank()) tr("Private number") else t.names.of(r.number)
            val whenText = TextSpeech.whenSaid(r.date, System.currentTimeMillis(), time = time)
            add(Control(CallWords.recentLabel(r, who), spoken = CallWords.recent(r, who, whenText), emphasized = r.isNew) {
                if (r.number.isBlank()) state.say(tr("This number is private, so it can't be called back."))
                else state.go(Screen.ConfirmCall(TileSpec.Contact(who, r.number)))
            })
        }
    }
    if (list != null && list!!.isEmpty()) {
        LaunchedEffect(Unit) {
            if (!CallLogStore.allowed(context)) state.say(tr("Recent calls need call history access. A helper can allow it in Setup, Phone app."))
        }
    }
    ChoiceList(state, if (list == null) tr("Recent calls…") else tr("Recent calls"), choices = choices)
}

/** Making RP Launcher the phone app. Calls work exactly as before; only the screens change. */
@Composable
fun PhoneSwitchScreen(state: AppState) {
    val context = LocalContext.current
    var isDefault by remember { mutableStateOf(PhoneRole.isDefault(context)) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefault = PhoneRole.isDefault(context)
        if (isDefault) {
            RpInCallService.channels(context)
            state.say(tr("RP Launcher is now the phone app. Calls ring as before, with a big call screen. Allow the questions that follow."))
            val ask = buildList {
                add(Manifest.permission.CALL_PHONE)
                add(Manifest.permission.READ_CALL_LOG)
                add(Manifest.permission.WRITE_CALL_LOG)
                add(Manifest.permission.READ_CONTACTS)
                if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            runCatching { permissions.launch(ask.toTypedArray()) }
        } else {
            state.say(tr("RP Launcher is not the phone app. Nothing was changed."))
        }
    }
    LifecycleResumeEffect(Unit) {
        isDefault = PhoneRole.isDefault(context)
        onPauseOrDispose { }
    }
    val current = remember(isDefault) { PhoneRole.currentAppName(context) }
    val explain = if (isDefault) {
        tr("RP Launcher is the phone app. Incoming calls show the caller's name large and say it aloud, with big Answer and Decline buttons. During a call: Hang up, Speaker, Mute, Louder, Quieter and Keypad. To go back, tap Switch back.")
    } else {
        tr("Right now, calls use %s. RP Launcher can be the phone app instead: a big call screen that says who's calling, a keypad that speaks, and recent calls read aloud. Emergency calls work as on any phone. You can switch back at any time.", current ?: tr("the phone's own app"))
    }
    LaunchedEffect(isDefault) { state.say(explain) }
    HelperColumn(state) {
        SettingRow(tr("Back to setup")) { state.go(Screen.Settings) }
        Words(state, explain)
        if (!isDefault) {
            SettingRow(tr("Switch now")) {
                val roles = context.getSystemService(RoleManager::class.java)
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    runCatching { role.launch(roles.createRequestRoleIntent(RoleManager.ROLE_DIALER)) }
                        .onFailure { state.say(tr("This phone didn't allow the switch.")) }
                } else {
                    state.say(tr("This phone can't use RP Launcher for calls."))
                }
            }
        } else {
            SettingRow(tr("Allow call history and contacts")) {
                permissions.launch(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG, Manifest.permission.READ_CONTACTS))
            }
            SettingRow(tr("Switch back to another phone app")) {
                state.start(PhoneRole.defaultAppsSettings(), tr("Could not open the setting"))
            }
        }
    }
}

/** A voicemail number read out key by key: "star 8 6", "plus 1 6 5 0 …". */
internal fun voicemailSpoken(number: String): String =
    number.filter { it.isDigit() || it in "+*#" }.map { CallWords.key(it.toString()) }.joinToString(" ")

/**
 * Voicemail: says and shows the number it will call before calling, so a wrong number in the
 * phone's settings (a scam number, say) is caught instead of called.
 */
@Composable
fun VoicemailScreen(state: AppState) {
    val context = LocalContext.current
    val custom = state.config.voicemailNumber
    val phoneNumber = remember { PhoneRole.phoneVoicemailNumber(context) }
    val number = custom ?: phoneNumber
    var pending by remember { mutableStateOf(false) }
    val place = {
        if (!PhoneRole.voicemail(context, custom)) state.say(tr("Voicemail couldn't be called. A helper can check the voicemail number in Setup."))
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (pending && ok) place() else if (pending) state.say(tr("Calls need the phone permission. A helper can allow it in Setup."))
        pending = false
    }
    val where = if (custom != null) tr("the number set in Setup") else tr("your phone's voicemail setting")
    val about = when {
        number == null -> tr("The phone didn't say which number it uses for voicemail.")
        else -> tr("Voicemail calls %s, from %s.", CallWords.grouped(number), where)
    }
    LaunchedEffect(Unit) {
        state.say(
            if (number == null) tr("Voicemail. %s Tap Call voicemail to try, or Back.", about)
            else tr("Voicemail. It calls %s. If that isn't your voicemail, tap Back, and ask a helper to set the right number in Setup.", voicemailSpoken(number)),
        )
    }
    ChoiceList(
        state, tr("Voicemail"),
        choices = listOf(
            Control(tr("Call voicemail"), spoken = tr("Call voicemail"), icon = TileIcon.Vector(Icons.Filled.Call), emphasized = true) {
                when {
                    Demo.on -> state.say(tr("Demo mode, so no call is made."))
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED -> {
                        state.say(tr("Calling voicemail"))
                        place()
                    }
                    else -> {
                        pending = true
                        ask.launch(Manifest.permission.CALL_PHONE)
                    }
                }
            },
            Control(if (number == null) tr("Number not known") else tr("Calls %s", CallWords.grouped(number)), spoken = about) { state.say(about) },
            Control(tr("Back"), spoken = tr("Back to Phone")) { state.go(Screen.PhoneHome) },
        ),
    )
}

/** Setup: which number Voicemail calls. The phone's own, or one a helper types in. */
@Composable
fun VoicemailSetupScreen(state: AppState) {
    val context = LocalContext.current
    val custom = state.config.voicemailNumber
    var phoneNumber by remember { mutableStateOf(PhoneRole.phoneVoicemailNumber(context)) }
    LifecycleResumeEffect(Unit) {
        phoneNumber = PhoneRole.phoneVoicemailNumber(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) { state.say(tr("Voicemail number")) }
    HelperColumn(state) {
        SettingRow(tr("Back")) { state.go(Screen.Settings) }
        Words(
            state,
            buildString {
                append(
                    when {
                        custom != null -> tr("Voicemail calls %s, the number set here.", CallWords.grouped(custom))
                        phoneNumber != null -> tr("Voicemail calls %s, the number in the phone's own voicemail setting.", CallWords.grouped(phoneNumber!!))
                        else -> tr("The phone doesn't say which number it uses for voicemail.")
                    },
                )
                if (custom != null && phoneNumber != null && PhoneRole.lastDigits(phoneNumber!!) != PhoneRole.lastDigits(custom)) {
                    append(tr(" The phone's own setting has %s.", CallWords.grouped(phoneNumber!!)))
                }
                append(tr(" If it isn't your carrier's voicemail number, type the right one. Your carrier's website or support line can tell you what it is."))
            },
        )
        SettingRow(tr("Type the voicemail number")) { state.go(Screen.NumberPad(NumberPurpose.VOICEMAIL)) }
        if (custom != null) {
            SettingRow(tr("Use the phone's own voicemail number")) {
                state.update { it.copy(voicemailNumber = null) }
                state.say(if (phoneNumber != null) tr("Voicemail calls %s again", voicemailSpoken(phoneNumber!!)) else tr("Using the phone's own voicemail number"))
            }
        }
        SettingRow(tr("Open the phone's voicemail settings")) {
            state.start(PhoneRole.voicemailSettings(), tr("This phone doesn't have voicemail settings to open"))
        }
    }
}
