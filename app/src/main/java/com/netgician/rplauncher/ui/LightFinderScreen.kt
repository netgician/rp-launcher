package com.netgician.rplauncher.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.netgician.rplauncher.i18n.tr
import com.netgician.rplauncher.model.LightRules
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

/**
 * Measures how much light the back camera sees: the picture's brightness, adjusted for the
 * camera's own exposure (so a dim room and a bright window really differ), as 0 to 1.
 */
private class LightMeter(private val context: Context) {
    private val worker = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    @Volatile private var exposureNs = 0L
    @Volatile private var iso = 0
    var level by mutableFloatStateOf(-1f)
        private set

    @OptIn(ExperimentalCamera2Interop::class)
    fun bind(owner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            runCatching {
                val builder = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        ).build(),
                    )
                Camera2Interop.Extender(builder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { exposureNs = it }
                        result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { iso = it }
                    }
                })
                val analysis = builder.build()
                analysis.setAnalyzer(worker) { image ->
                    runCatching {
                        val plane = image.planes[0]
                        val buf = plane.buffer
                        var sum = 0L
                        var n = 0
                        val rowStride = plane.rowStride
                        var y = 0
                        while (y < image.height) {
                            var x = 0
                            while (x < image.width) {
                                sum += buf.get(y * rowStride + x).toInt() and 0xFF
                                n++
                                x += 8
                            }
                            y += 8
                        }
                        val mean = if (n > 0) sum.toDouble() / n else 0.0
                        level = LightRules.level(mean, exposureNs, iso)
                    }
                    image.close()
                }
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun unbind() {
        runCatching { provider?.unbindAll() }
        worker.shutdown()
    }
}

/** A soft, steady tone whose pitch follows the light: low in the dark, high toward a lamp or window. */
private class ToneLoop {
    @Volatile var frequency = 0.0
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            val rate = 22_050
            val size = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2048)
            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(size)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull() ?: return@Thread
            track.play()
            val chunk = ShortArray(1024)
            var phase = 0.0
            while (running) {
                val f = frequency
                for (i in chunk.indices) {
                    chunk[i] = if (f <= 0) 0 else (sin(phase) * 6_000).toInt().toShort()
                    phase += 2 * PI * f / rate
                    if (phase > 2 * PI) phase -= 2 * PI
                }
                track.write(chunk, 0, chunk.size)
            }
            runCatching { track.stop() }
            track.release()
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread = null
    }
}

/**
 * The Light finder tile: point the back of the phone around; a tone rises toward light, and the
 * word for how bright it is (Dark, Dim, Medium, Bright, Very bright) is shown large and said
 * when it changes. Helps find a window, a lamp, or whether a light is on.
 */
@Composable
fun LightFinderScreen(state: AppState) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        allowed = ok
        if (!ok) state.say(tr("The camera is not allowed. A helper can allow it in the Setup checklist."))
    }
    val meter = remember { LightMeter(context) }
    val tone = remember { ToneLoop() }
    var sound by remember { mutableStateOf(true) }
    DisposableEffect(allowed) {
        if (allowed) meter.bind(owner)
        onDispose { meter.unbind() }
    }
    DisposableEffect(sound) {
        if (sound) tone.start() else tone.stop()
        onDispose { tone.stop() }
    }
    LaunchedEffect(Unit) {
        state.say(tr("Light finder. Point the back of the phone around. The tone gets higher toward light."))
        if (!allowed) ask.launch(Manifest.permission.CAMERA)
    }
    val level = meter.level
    tone.frequency = if (level < 0) 0.0 else LightRules.pitch(level)
    // The word is said when it changes and stays for a moment, not on every flicker.
    var said by remember { mutableStateOf<String?>(null) }
    val word = if (level < 0) null else LightRules.word(level)
    LaunchedEffect(word) {
        if (word == null) return@LaunchedEffect
        delay(1_200)
        if (word != said) {
            said = word
            state.say(word)
        }
    }
    val shown = when {
        !allowed -> tr("Camera not allowed")
        word == null -> tr("Starting…")
        else -> "$word · ${(level * 100).toInt()}%"
    }
    ChoiceList(
        state, shown, spokenTitle = word ?: shown,
        choices = listOf(
            Control(if (sound) tr("Sound off") else tr("Sound on"), spoken = if (sound) tr("Turn the tone off") else tr("Turn the tone on")) {
                sound = !sound
                state.say(if (sound) tr("Tone on") else tr("Tone off"))
            },
            Control(tr("Say it"), spoken = tr("Say how bright it is"), emphasized = true) {
                state.say(word?.let { tr("%s, %s percent", it, (level * 100).toInt()) } ?: shown)
            },
            Control(tr("Back"), spoken = tr("Back to the home screen")) { state.go(Screen.Home) },
        ),
    )
}
