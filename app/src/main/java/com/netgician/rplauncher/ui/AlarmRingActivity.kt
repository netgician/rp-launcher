package com.netgician.rplauncher.ui

import android.graphics.Color
import android.os.Bundle
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.netgician.rplauncher.i18n.Lang
import com.netgician.rplauncher.i18n.tr
import com.netgician.rplauncher.model.AlarmRules
import com.netgician.rplauncher.model.AlarmWords
import com.netgician.rplauncher.model.AutoNight
import com.netgician.rplauncher.model.LauncherConfig
import com.netgician.rplauncher.model.NightMode
import com.netgician.rplauncher.system.Alarms
import com.netgician.rplauncher.system.ConfigStore
import com.netgician.rplauncher.system.LightSensor
import com.netgician.rplauncher.system.RingSound
import com.netgician.rplauncher.system.Speaker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * An alarm or reminder going off, over the lock screen and inside the viewing window: the
 * time (alarm) or the message (reminder), large and said aloud every 30 seconds, with a big
 * Stop or Done and "10 more minutes". When two go off together, they're shown one after another.
 */
class AlarmRingActivity : ComponentActivity() {
    private lateinit var speaker: Speaker
    private lateinit var config: LauncherConfig
    private lateinit var sound: RingSound
    private var lightSensor: LightSensor? = null
    private var night by mutableStateOf(NightNow.active)
    private var leaving = false

    private fun refreshNightByClock() {
        if (config.autoNight == AutoNight.CLOCK) {
            val now = Calendar.getInstance()
            night = NightMode.byClock(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), config.nightFromHour, config.nightUntilHour)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.BLACK), navigationBarStyle = SystemBarStyle.dark(Color.BLACK))
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        config = ConfigStore(this).load()
        refreshNightByClock()
        lightSensor = LightSensor(this) { lux -> if (config.autoNight == AutoNight.LIGHT) night = NightMode.byLight(lux, night) }
        speaker = Speaker(this).apply {
            rate = config.speechRate
            pitch = config.speechPitch
            voiceName = config.voiceName
        }
        sound = RingSound(this)
        // Back would hide the screen and leave it ringing out of sight: only Stop or Done ends it.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val showing = Alarms.ringing.value.firstOrNull()?.first?.let { Alarms.get(this@AlarmRingActivity, it) }
                speaker.speak(if (showing?.isReminder == true) tr("Tap Done when you're finished.") else tr("Tap Stop to turn it off."))
            }
        })
        Alarms.load(this)
        if (Alarms.ringing.value.isEmpty()) {
            finish()
            return
        }
        val is24 = DateFormat.is24HourFormat(this)

        // Closes when nothing is ringing any more: Stop or Done here, from the notification, or by itself.
        lifecycleScope.launch {
            Alarms.ringing.collect { list ->
                if (list.isEmpty()) {
                    sound.stop()
                    delay(if (leaving) 1_500 else 300)
                    finish()
                }
            }
        }
        // Said now and every 30 seconds, for whichever is showing.
        lifecycleScope.launch {
            delay(700)
            while (true) {
                val id = Alarms.ringing.value.firstOrNull()?.first ?: break
                Alarms.get(this@AlarmRingActivity, id)?.let { speaker.speak(AlarmWords.ringing(it, is24, Lang.locale)) }
                delay(30_000)
            }
        }
        // Each one goes quiet by itself after a while.
        lifecycleScope.launch {
            while (true) {
                val list = Alarms.ringing.value
                if (list.isEmpty()) break
                val now = System.currentTimeMillis()
                list.filter { (_, since) -> now - since >= AlarmRules.RING_FOR_MS }.forEach { (id, _) -> Alarms.stopRinging(this@AlarmRingActivity, id) }
                delay(10_000)
            }
        }

        setContent {
            val theme = if (night && config.autoNight != AutoNight.OFF) config.nightTheme else config.theme
            val style = remember(theme) { LvStyle(theme, AtkinsonFamily, config.textScale) }
            LaunchedEffect(theme) {
                val attrs = window.attributes
                attrs.screenBrightness = theme.windowBrightness ?: config.brightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = attrs
            }
            CompositionLocalProvider(LocalLv provides style, LocalWindowFrame provides config.windowFrame) {
                val ringing by Alarms.ringing.collectAsState()
                val id = ringing.firstOrNull()?.first
                val a = id?.let { Alarms.get(this@AlarmRingActivity, it) }
                InWindow(config.window) {
                    if (a == null) return@InWindow
                    val say: (String) -> Unit = { speaker.speak(it) }
                    val time = AlarmWords.time(a.hour, a.minute, is24, Lang.locale)
                    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SpokenText(
                            if (a.isReminder) tr("Reminder") else tr("Alarm"),
                            Modifier.fillMaxWidth().height((48f * style.textScale).dp), say,
                            spoken = AlarmWords.ringing(a, is24, Lang.locale), maxSp = 32f,
                        )
                        // The time for an alarm; the message for a reminder, as large as fits.
                        SpokenText(
                            if (a.isReminder) a.message!! else time,
                            Modifier.fillMaxWidth().weight(1.2f), say,
                            spoken = AlarmWords.ringing(a, is24, Lang.locale), maxSp = if (a.isReminder) 44f else 96f, maxLines = 4,
                        )
                        Tile(
                            if (a.isReminder) tr("Done") else tr("Stop"), Modifier.fillMaxWidth().weight(1.4f),
                            spoken = if (a.isReminder) tr("Done with this reminder") else tr("Stop the alarm"),
                            icon = TileIcon.Vector(if (a.isReminder) Icons.Filled.Check else Icons.Filled.Close), emphasized = true,
                            onLongPress = { say(if (a.isReminder) tr("Done with this reminder") else tr("Stop the alarm")) },
                        ) {
                            leaving = Alarms.ringing.value.size <= 1
                            speaker.speak(if (a.isReminder) tr("Done") else tr("Alarm stopped"))
                            Alarms.stopRinging(this@AlarmRingActivity, a.id)
                        }
                        Tile(
                            tr("10 more minutes"), Modifier.fillMaxWidth().weight(0.8f), spoken = tr("Ring again in 10 minutes"),
                            icon = TileIcon.Vector(Icons.Filled.Refresh), onLongPress = { say(tr("Ring again in 10 minutes")) },
                        ) {
                            leaving = Alarms.ringing.value.size <= 1
                            speaker.speak(tr("It rings again in 10 minutes."))
                            Alarms.snooze(this@AlarmRingActivity, a.id)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Alarms.ringing.value.isNotEmpty()) {
            Alarms.takeOver(this)
            sound.start()
        }
    }

    override fun onStart() {
        super.onStart()
        lightSensor?.start()
        refreshNightByClock()
    }

    override fun onStop() {
        super.onStop()
        lightSensor?.stop()
    }

    override fun onDestroy() {
        if (::sound.isInitialized) sound.stop()
        if (::speaker.isInitialized) speaker.shutdown()
        super.onDestroy()
    }
}
