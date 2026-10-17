package com.netgician.rplauncher.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.netgician.rplauncher.texts.MmsDownloads
import com.netgician.rplauncher.texts.MyNumbers
import com.netgician.rplauncher.texts.People
import com.netgician.rplauncher.texts.TextNotifications
import com.netgician.rplauncher.texts.TextRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** A block of words on a helper screen. Tap to hear it. */
@Composable
internal fun Words(state: AppState, text: String) {
    val lv = LocalLv.current
    Box(
        Modifier
            .fillMaxWidth()
            .border(BorderStroke(2.dp, lv.accent), RoundedCornerShape(12.dp))
            .semantics { contentDescription = text }
            .padding(12.dp),
    ) {
        FitText(text, lv.fg, maxSp = 22f * lv.textScale, minSp = 14f, maxLines = 12, align = TextAlign.Start, modifier = Modifier.fillMaxWidth())
    }
}

/** The RCS reminder, in the words of the app being switched away from. */
private fun rcsAdvice(app: String?): String = when (app) {
    "Google Messages" ->
        "Before you switch: Google Messages has chat features called RCS chats. Only Google Messages can receive them, " +
            "so turn them off first, or some messages may never reach RP Launcher. In Google Messages, tap your profile " +
            "picture, then Messages settings, then RCS chats, and turn RCS chats off. Then come back and tap Switch now."
    null ->
        "Before you switch: if your texting app has chat features (sometimes called RCS or chat), turn them off in that " +
            "app's settings first, so no messages are missed. Then tap Switch now."
    else ->
        "Before you switch: $app has chat features. Only $app can receive those, so turn them off in its settings first " +
            "(look for chat settings or RCS), or some messages may never reach RP Launcher. Then come back and tap Switch now."
}

/** Making RP Launcher the texting app, with the RCS reminder first. Switching back is always one step. */
@Composable
fun TextsSwitchScreen(state: AppState) {
    val context = LocalContext.current
    var isDefault by remember { mutableStateOf(TextRole.isDefault(context)) }
    val rcs = remember { TextRole.rcsApp(context) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        state.texts.names.forget()
    }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefault = TextRole.isDefault(context)
        if (isDefault) {
            state.update { it.copy(textsElsewhere = false) }
            state.say(
                "RP Launcher is now the texting app. All your texts are still here. " +
                    "Next, allow the questions that follow, then tap Check texts to test it with your carrier.",
            )
            TextNotifications.ensureChannels(context)
            val ask = buildList {
                add(Manifest.permission.READ_CONTACTS)
                add(Manifest.permission.READ_PHONE_NUMBERS)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            runCatching { permissions.launch(ask.toTypedArray()) }
            state.scope.launch(Dispatchers.IO) { runCatching { MmsDownloads.resume(context) } }
        } else {
            state.say("RP Launcher is not the texting app. Nothing was changed.")
        }
    }
    LifecycleResumeEffect(Unit) {
        isDefault = TextRole.isDefault(context)
        onPauseOrDispose { }
    }
    val current = remember(isDefault) { TextRole.currentAppName(context) }
    val explain = if (isDefault) {
        "RP Launcher is the texting app. Texts, picture texts and group texts all come here, and are kept in the " +
            "phone's shared message store. To go back to another texting app, tap Switch back. Nothing is lost either way."
    } else {
        (if (current == null) "No texting app is set on this phone right now. " else "Right now, texts go to $current. ") + rcsAdvice(rcs)
    }
    LaunchedEffect(isDefault) { state.say(explain) }

    HelperColumn(state) {
        SettingRow("Back to setup") { state.go(Screen.Settings) }
        Words(state, explain)
        if (!isDefault) {
            if (rcs != null) {
                SettingRow("Open $rcs") {
                    val i = TextRole.previousAppIntent(context)
                    if (i != null) state.start(i) else state.say("$rcs couldn't be opened")
                }
            }
            if (state.config.textsElsewhere) {
                SettingRow("Texts tile opens", current ?: "the texting app") {
                    state.update { it.copy(textsElsewhere = false) }
                    state.say("The Texts tile will ask again")
                }
            }
            SettingRow(if (rcs != null) "Chats are off. Switch now" else "Switch now") {
                val roles = context.getSystemService(RoleManager::class.java)
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    runCatching { role.launch(roles.createRequestRoleIntent(RoleManager.ROLE_SMS)) }
                        .onFailure { state.say("This phone didn't allow the switch.") }
                } else {
                    state.say("This phone can't use RP Launcher for texts.")
                }
            }
        } else {
            SettingRow("Open Texts") { state.go(Screen.Texts) }
            SettingRow("Check texts") { state.go(Screen.TextsCheck) }
            SettingRow("Switch back to another texting app") {
                state.start(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), "Could not open the setting")
            }
        }
    }
}

/** Sends test texts to this phone's own number, so a helper knows texts work with this carrier. */
@Composable
fun TextsCheckScreen(state: AppState) {
    val context = LocalContext.current
    val t = state.texts
    val mine = remember(t.checkLog.size, state.screen) { MyNumbers.get(context).firstOrNull() }
    val pickOther = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val c = r.data?.data?.let { readPhoneContact(context, it) }
        if (c == null) state.say("No one picked") else t.check(CheckStep.GROUP, c.number)
    }
    val explain = "Check texts sends tests to this phone's own number, then listens for them to come back. " +
        "Picture and group texts need mobile data turned on."
    LaunchedEffect(Unit) { state.say(explain) }
    HelperColumn(state) {
        SettingRow("Back") { state.go(Screen.TextsSwitch) }
        Words(state, explain)
        if (mine == null) {
            SettingRow("Type this phone's number") { state.go(Screen.NumberPad(NumberPurpose.OWN_NUMBER)) }
        } else {
            SettingRow("This phone's number", mine) { state.say("This phone's number is ${People.spoken(mine)}") }
            SettingRow("1. Send a test text") { t.check(CheckStep.TEXT) }
            SettingRow("2. Send a test picture text") { t.check(CheckStep.PICTURE) }
            SettingRow("3. Send a test group text") {
                state.say("Pick one more person. They'll get a short test message.")
                runCatching {
                    pickOther.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                }.onFailure { state.say("No contacts app found") }
            }
            SettingRow("Wrong number? Type it again") { state.go(Screen.NumberPad(NumberPurpose.OWN_NUMBER)) }
        }
        t.checkLog.asReversed().take(12).forEach { line -> Words(state, line) }
    }
}
