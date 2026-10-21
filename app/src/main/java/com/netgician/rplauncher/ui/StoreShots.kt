package com.netgician.rplauncher.ui

import android.content.Context
import com.netgician.rplauncher.model.AutoNight
import com.netgician.rplauncher.model.TileSpec
import com.netgician.rplauncher.i18n.Lang
import com.netgician.rplauncher.model.Alarm
import com.netgician.rplauncher.model.AlarmRules
import com.netgician.rplauncher.model.TimerRules
import com.netgician.rplauncher.system.Alarms
import com.netgician.rplauncher.model.ViewingWindow
import com.netgician.rplauncher.system.Timer
import kotlinx.coroutines.launch

/**
 * Test builds only: the build machine opens each screen in Demo mode and photographs it for the
 * app stores (scripts/store_screenshots.sh). Release builds ignore this.
 */
object StoreShots {
    private val TOOL_SCREENS = setOf(
        "tools-home", "battery-low", "alarms", "reminders", "alarm-edit", "alarm-time", "alarm-ampm", "alarm-days",
        "alarm-pickdays", "reminder-message", "alarm-ring", "reminder-ring", "calculator", "music", "notes", "light",
    )

    const val ACTION = "com.netgician.rplauncher.STORE_SHOT"
    const val EXTRA_SCREEN = "screen"

    private val TILES = listOf(
        TileSpec.Phone, TileSpec.Messages, TileSpec.See, TileSpec.Torch,
        TileSpec.Sos, TileSpec.Contact(Demo.CALLER_NAME, Demo.CALLER_NUMBER),
    )

    /** For checking the Timer tile's screens (scripts/check_screens.sh). */
    private val TIMER_TILES = listOf(
        TileSpec.Phone, TileSpec.Messages, TileSpec.Timer, TileSpec.See, TileSpec.Torch, TileSpec.Sos,
    )

    /** For checking the new tools' screens. */
    private val TOOL_TILES = listOf(
        TileSpec.Clock, TileSpec.Alarms, TileSpec.Reminders, TileSpec.Calculator, TileSpec.Music, TileSpec.Notes, TileSpec.LightFinder,
    )

    /** Example alarms and reminders, the same each time. */
    private fun examples(context: Context) {
        Alarms.load(context).forEach { Alarms.delete(context, it.id) }
        Alarms.put(context, Alarm(1, 7, 30, days = AlarmRules.WEEKDAYS))
        Alarms.put(context, Alarm(2, 9, 0, days = AlarmRules.WEEKEND, on = false))
        Alarms.put(context, Alarm(3, 8, 0, days = AlarmRules.EVERY_DAY, message = if (Lang.spanish) "Toma tus pastillas" else "Take your pills"))
        Alarms.put(context, Alarm(4, 17, 0, days = setOf(1, 3, 5), message = if (Lang.spanish) "Llama a Mary" else "Call Mary"))
    }

    const val EXTRA_LANGUAGE = "lang"

    /** Digits already typed on the timer's number pad, for checking the min and sec boxes. */
    private var typedTime = ""

    fun takeTypedTime(): String = typedTime.also { typedTime = "" }

    fun show(state: AppState, context: Context, screen: String, language: String? = null) {
        if (!Demo.on) {
            Demo.set(true)
            state.texts.demoChanged()
        }
        val theme = when (screen) {
            "home-night" -> "night_red"
            "picture", "recents", "checklist" -> "dark"
            else -> "yellow"
        }
        state.update {
            it.copy(
                windowChecked = true,
                // A roomy window, so the store pictures are easy to read at thumbnail size.
                window = ViewingWindow(0.94f, 0.8f, 0.5f).clamped(),
                windowCheckedAt = System.currentTimeMillis(),
                tiles = when {
                    screen.startsWith("timer") -> TIMER_TILES
                    screen in TOOL_SCREENS -> TOOL_TILES
                    else -> TILES
                },
                themeId = theme,
                autoNight = AutoNight.OFF,
                language = language ?: it.language,
                sosContacts = listOf(TileSpec.Contact(Demo.CALLER_NAME, Demo.CALLER_NUMBER), TileSpec.Contact("Bob", "+19195550111")),
            )
        }
        Timer.inAppCheck = screen != "timer-asleep-locked"
        when (screen) {
            "home", "home-night" -> {
                state.homePage = 0
                state.go(Screen.Home)
            }
            "texts" -> state.go(Screen.Texts)
            "conversation" -> state.go(Screen.Conversation(1))
            "group" -> state.go(Screen.Conversation(3))
            "picture" -> state.go(Screen.Picture(3, listOf(Demo.PICTURE_ID), 0))
            "phone" -> state.go(Screen.PhoneHome)
            "recents" -> state.go(Screen.Recents)
            "call" -> com.netgician.rplauncher.calls.CallActivity.openDemo(context, true, Demo.CALLER_NAME, Demo.CALLER_NUMBER)
            "sos" -> state.go(Screen.Sos(practice = true))
            "checklist" -> state.go(Screen.Checklist(firstRun = false))
            "pages" -> state.go(Screen.Pages)
            "voicemail" -> state.go(Screen.VoicemailMessage(1))
            "tools-home", "battery-low" -> {
                examples(context)
                state.homePage = 0
                state.go(Screen.Home)
            }
            "alarms" -> {
                examples(context)
                state.go(Screen.AlarmList(reminders = false))
            }
            "reminders" -> {
                examples(context)
                state.go(Screen.AlarmList(reminders = true))
            }
            "alarm-edit" -> {
                examples(context)
                state.go(Screen.AlarmEdit(3, reminder = true))
            }
            "alarm-time" -> {
                state.alarmDraft = AlarmDraft(reminder = false)
                typedTime = "730"
                state.go(Screen.Home)
                state.scope.launch {
                    kotlinx.coroutines.delay(800)
                    state.go(Screen.AlarmTime)
                }
            }
            "alarm-ampm" -> {
                state.alarmDraft = AlarmDraft(reminder = false, hour = 7, minute = 30)
                state.go(Screen.AlarmAmPm)
            }
            "alarm-days" -> {
                state.alarmDraft = AlarmDraft(reminder = false, hour = 7, minute = 30)
                state.go(Screen.AlarmDays)
            }
            "alarm-pickdays" -> {
                state.alarmDraft = AlarmDraft(reminder = false, hour = 7, minute = 30, days = setOf(1, 3, 5))
                state.go(Screen.AlarmPickDays)
            }
            "reminder-message" -> {
                state.alarmDraft = AlarmDraft(reminder = true, message = if (Lang.spanish) "Toma tus pastillas" else "Take your pills")
                state.go(Screen.AlarmMessage)
            }
            "alarm-ring", "reminder-ring" -> {
                examples(context)
                state.go(Screen.Home)
                Alarms.ring(context, if (screen == "alarm-ring") 1L else 3L)
            }
            "calculator" -> state.go(Screen.Calculator)
            "music" -> state.go(Screen.Music)
            "notes" -> state.go(Screen.Notes)
            "light" -> state.go(Screen.LightFinder)
            "timer-pick" -> {
                Timer.cancel(context)
                state.go(Screen.Timer)
            }
            "timer" -> {
                Timer.cancel(context)
                Timer.start(context, 10 * TimerRules.MINUTE)
                state.go(Screen.Timer)
            }
            "timer-paused" -> {
                Timer.cancel(context)
                Timer.start(context, 25 * TimerRules.MINUTE + 30_000)
                Timer.pause(context)
                state.go(Screen.Timer)
            }
            "timer-home" -> {
                Timer.cancel(context)
                Timer.start(context, 10 * TimerRules.MINUTE)
                state.homePage = 0
                state.go(Screen.Home)
            }
            "timer-minutes" -> {
                Timer.cancel(context)
                state.go(Screen.NumberPad(NumberPurpose.TIMER))
            }
            "timer-typed-130", "timer-typed-600" -> {
                Timer.cancel(context)
                typedTime = screen.substringAfterLast('-')
                // Home first, so the number pad opens fresh with the digits in it.
                state.go(Screen.Home)
                state.scope.launch {
                    kotlinx.coroutines.delay(800)
                    state.go(Screen.NumberPad(NumberPurpose.TIMER))
                }
            }
            // Only the alarm can ring this one: the in-app check is off, as when the phone sleeps.
            "timer-asleep-locked" -> {
                Timer.cancel(context)
                Timer.inAppCheck = false
                Timer.start(context, 20_000)
                state.homePage = 0
                state.go(Screen.Home)
            }
            // Rings a few seconds later: on screen, or with the screen off for "-locked".
            "timer-ring", "timer-ring-locked" -> {
                Timer.cancel(context)
                Timer.start(context, 3_000)
                state.homePage = 0
                state.go(Screen.Home)
            }
        }
    }
}
