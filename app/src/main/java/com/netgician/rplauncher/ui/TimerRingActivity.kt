package com.netgician.rplauncher.ui

import android.graphics.Color
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.netgician.rplauncher.i18n.tr
import com.netgician.rplauncher.model.AutoNight
import com.netgician.rplauncher.model.LauncherConfig
import com.netgician.rplauncher.model.NightMode
import com.netgician.rplauncher.model.TimerRules
import com.netgician.rplauncher.model.TimerState
import com.netgician.rplauncher.model.TimerWords
import com.netgician.rplauncher.system.ConfigStore
import com.netgician.rplauncher.system.LightSensor
import com.netgician.rplauncher.system.Speaker
import com.netgician.rplauncher.system.Timer
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * The timer going off: "Timer done", with a big Stop and 1 more minute, over the lock screen
 * and inside the viewing window, in the person's colours. The sound comes from the timer's
 * notification; this screen plays it itself only when notifications are off.
 */
class TimerRingActivity : ComponentActivity() {
    private lateinit var speaker: Speaker
    private lateinit var config: LauncherConfig
    private var lightSensor: LightSensor? = null
    private var night by mutableStateOf(NightNow.active)
    private var ownSound: Ringtone? = null

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
        Timer.load(this)
        if (Timer.state.value.phase != TimerState.Phase.RINGING) {
            finish()
            return
        }
        if (!Timer.notificationsAllowed(this)) playOwnSound()

        setContent {
            val theme = if (night && config.autoNight != AutoNight.OFF) config.nightTheme else config.theme
            val style = remember(theme) { LvStyle(theme, AtkinsonFamily, config.textScale) }
            LaunchedEffect(theme) {
                val attrs = window.attributes
                attrs.screenBrightness = theme.windowBrightness ?: config.brightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = attrs
            }
            CompositionLocalProvider(LocalLv provides style, LocalWindowFrame provides config.windowFrame) {
                val s by Timer.state.collectAsState()
                var leaving by remember { mutableStateOf(false) }
                // Stopped here, from the notification, or by 1 more minute: close.
                LaunchedEffect(s.phase) {
                    if (s.phase != TimerState.Phase.RINGING) {
                        stopOwnSound()
                        delay(if (leaving) 1_500 else 300)
                        finish()
                    }
                }
                // Said now and every 30 seconds while it rings.
                LaunchedEffect(Unit) {
                    delay(700)
                    while (Timer.state.value.phase == TimerState.Phase.RINGING) {
                        speaker.speak(tr("Timer done. Tap Stop."))
                        delay(30_000)
                    }
                }
                // It goes quiet by itself after a while, like a kitchen timer left ringing.
                LaunchedEffect(s.rangAt) {
                    if (s.phase == TimerState.Phase.RINGING) {
                        delay((s.rangAt + TimerRules.RING_FOR_MS - System.currentTimeMillis()).coerceAtLeast(0L))
                        if (Timer.state.value.phase == TimerState.Phase.RINGING) Timer.stop(this@TimerRingActivity)
                    }
                }
                InWindow(config.window) {
                    val say: (String) -> Unit = { speaker.speak(it) }
                    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SpokenText(
                            tr("Timer done"), Modifier.fillMaxWidth().height((64f * style.textScale).dp), say,
                            spoken = tr("Timer done. Tap Stop."), maxSp = 40f,
                        )
                        val which = TimerWords.which(s)
                        if (which.isNotEmpty()) {
                            SpokenText(which, Modifier.fillMaxWidth().height((36f * style.textScale).dp), say, maxSp = 22f)
                        }
                        Tile(
                            tr("Stop"), Modifier.fillMaxWidth().weight(2f), spoken = tr("Stop the timer"),
                            icon = TileIcon.Vector(Icons.Filled.Close), emphasized = true, onLongPress = { say(tr("Stop the timer")) },
                        ) {
                            leaving = true
                            speaker.speak(tr("Timer stopped"))
                            Timer.stop(this@TimerRingActivity)
                        }
                        Tile(
                            tr("1 more minute"), Modifier.fillMaxWidth().weight(1f), spoken = tr("Ring again in 1 minute"),
                            icon = TileIcon.Vector(Icons.Filled.Add), onLongPress = { say(tr("Ring again in 1 minute")) },
                        ) {
                            leaving = true
                            speaker.speak(tr("It rings again in 1 minute."))
                            Timer.addMinute(this@TimerRingActivity)
                        }
                    }
                }
            }
        }
    }

    /** The alarm sound, looping, at alarm volume: only when there's no notification to play it. */
    private fun playOwnSound() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: return
        ownSound = runCatching {
            RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }.getOrNull()
    }

    private fun stopOwnSound() {
        runCatching { ownSound?.stop() }
        ownSound = null
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
        stopOwnSound()
        if (::speaker.isInitialized) speaker.shutdown()
        super.onDestroy()
    }
}
