package com.netgician.rplauncher.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Size
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.netgician.rplauncher.R
import com.netgician.rplauncher.model.CameraMath
import com.netgician.rplauncher.model.after
import com.netgician.rplauncher.ocr.RgbImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * The phone's camera as a magnifier, shown inside the viewing window. Closer and Wider zoom
 * (Wider goes below normal size on phones with a wide lens, which fits more of a scene into a
 * narrow field), Freeze holds a still picture to study, Light brightens dim scenes, and Colours
 * turns dark text on light paper into light text on black.
 */
class SeeCamera(context: Context) {
    val previewView = PreviewView(context).apply {
        // TextureView mode, so the colour filter applies and a frame can be frozen.
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
        setBackgroundColor(android.graphics.Color.BLACK)
    }

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private val worker = Executors.newSingleThreadExecutor()

    var ready by mutableStateOf(false)
        private set
    var zoom by mutableFloatStateOf(1f)
        private set
    var minZoom by mutableFloatStateOf(1f)
        private set
    var maxZoom by mutableFloatStateOf(1f)
        private set
    var frozen by mutableStateOf<Bitmap?>(null)
        private set
    var light by mutableStateOf(CameraMath.Light.OFF)
        private set
    var filter by mutableStateOf(CameraMath.Filter.NORMAL)
        private set

    val hasFlash: Boolean get() = camera?.cameraInfo?.hasFlashUnit() == true

    fun bind(owner: LifecycleOwner) {
        val ctx = previewView.context
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            runCatching {
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                // Stills for reading text: sharp enough for small print, small enough to read fast.
                val still = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setResolutionSelector(
                        ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(Size(2048, 1536), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        ).build(),
                    )
                    .build()
                p.unbindAll()
                val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, still)
                camera = cam
                capture = still
                cam.cameraInfo.zoomState.observe(owner) { z ->
                    minZoom = z.minZoomRatio
                    maxZoom = z.maxZoomRatio
                    zoom = z.zoomRatio
                }
                ready = true
                applyLight()
                applyFilter()
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun unbind() {
        runCatching { provider?.unbindAll() }
        camera = null
        capture = null
        ready = false
    }

    /** A full-resolution still, upright, at the current zoom. Null if the camera can't take one. */
    suspend fun photo(): Bitmap? = suspendCancellableCoroutine { cont ->
        val ic = capture
        if (ic == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        ic.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp = runCatching {
                    val raw = image.toBitmap()
                    val degrees = image.imageInfo.rotationDegrees
                    if (degrees == 0) raw
                    else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
                }.getOrNull()
                image.close()
                if (cont.isActive) cont.resume(bmp)
            }

            override fun onError(exception: ImageCaptureException) {
                if (cont.isActive) cont.resume(null)
            }
        })
    }

    /** What the screen shows right now, as a fallback when a still can't be taken. */
    fun screenPicture(): Bitmap? = runCatching { previewView.bitmap }.getOrNull()

    /** Returns false at the limit. */
    fun closer(): Boolean = setZoom(CameraMath.closer(zoom, maxZoom))
    fun wider(): Boolean = setZoom(CameraMath.wider(zoom, minZoom))

    private fun setZoom(next: Float): Boolean {
        if (abs(next - zoom) < 0.01f) return false
        zoom = next
        camera?.cameraControl?.setZoomRatio(next)
        return true
    }

    fun freeze(): Boolean {
        val b = runCatching { previewView.bitmap }.getOrNull() ?: return false
        frozen = b
        return true
    }

    fun live() {
        frozen = null
    }

    fun nextLight() {
        light = CameraMath.Light.entries.after(light)
        applyLight()
    }

    fun nextFilter() {
        filter = CameraMath.Filter.entries.after(filter)
        applyFilter()
    }

    private fun applyLight() {
        val cam = camera ?: return
        runCatching {
            cam.cameraControl.enableTorch(light == CameraMath.Light.TORCH && cam.cameraInfo.hasFlashUnit())
            val exposure = cam.cameraInfo.exposureState
            if (exposure.isExposureCompensationSupported) {
                val index = if (light == CameraMath.Light.OFF) 0 else exposure.exposureCompensationRange.upper
                cam.cameraControl.setExposureCompensationIndex(index)
            }
        }
    }

    private fun applyFilter() {
        val m = CameraMath.matrix(filter)
        if (m == null) {
            previewView.setLayerType(View.LAYER_TYPE_NONE, null)
        } else {
            previewView.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
        }
    }
}

private enum class SeeControl { READ, CLOSER, WIDER, FREEZE, LIGHT, COLOURS, HOME }

private const val SEE_INTRO = "See. Point the camera at what you want to look at. " +
    "Read reads printed text aloud. Closer and Wider zoom. Freeze holds the picture still."

/** Copies a picture into the text reader's format, scaled so its longest side is at most [maxSide]. */
internal fun Bitmap.toRgbImage(maxSide: Int = 2048): RgbImage {
    var b = if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this
    val scale = maxSide.toFloat() / maxOf(b.width, b.height)
    if (scale < 1f) {
        b = Bitmap.createScaledBitmap(
            b, (b.width * scale).toInt().coerceAtLeast(1), (b.height * scale).toInt().coerceAtLeast(1), true,
        )
    }
    val pixels = IntArray(b.width * b.height)
    b.getPixels(pixels, 0, b.width, 0, 0, b.width, b.height)
    return RgbImage(b.width, b.height, pixels)
}

@Composable
fun SeeScreen(state: AppState) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lv = LocalLv.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        state.say(if (ok) SEE_INTRO else "See needs the camera. You can allow it in the phone's settings.")
    }
    LaunchedEffect(Unit) {
        if (granted) state.say(SEE_INTRO) else ask.launch(Manifest.permission.CAMERA)
    }

    val see = remember { SeeCamera(context) }
    DisposableEffect(granted, lifecycleOwner) {
        if (granted) see.bind(lifecycleOwner)
        onDispose { see.unbind() }
    }

    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    var frozenScale by remember { mutableFloatStateOf(1f) }
    var frozenOffset by remember { mutableStateOf(Offset.Zero) }
    var page by remember { mutableIntStateOf(0) }

    val frozen = see.frozen
    val shownZoom = if (frozen != null) frozenScale else see.zoom
    val status = if (reading) "Reading…" else "${if (frozen != null) "Frozen" else "Live"} · ${CameraMath.shortZoom(shownZoom)}"
    val spokenStatus = "${if (frozen != null) "Frozen picture" else "Live picture"}, " +
        "${CameraMath.describeZoom(shownZoom)}, ${see.filter.label}, ${see.light.label}"

    InWindow(state.config.window) {
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.55f)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .semantics { contentDescription = "Camera picture. $spokenStatus" },
                contentAlignment = Alignment.Center,
            ) {
                if (!granted) {
                    FitText("The camera is not allowed", lv.fg, maxSp = 22f * lv.textScale, maxLines = 3)
                } else {
                    AndroidView(factory = { see.previewView }, modifier = Modifier.fillMaxSize())
                    if (frozen != null) {
                        val matrix = CameraMath.matrix(see.filter)
                        Image(
                            bitmap = frozen.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            colorFilter = matrix?.let { ColorFilter.colorMatrix(ColorMatrix(it)) },
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = frozenScale
                                    scaleY = frozenScale
                                    translationX = frozenOffset.x
                                    translationY = frozenOffset.y
                                }
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, gestureZoom, _ ->
                                        frozenScale = (frozenScale * gestureZoom).coerceIn(1f, CameraMath.FROZEN_MAX_ZOOM)
                                        frozenOffset = if (frozenScale <= 1f) Offset.Zero else frozenOffset + pan
                                    }
                                },
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(0.45f)) {
                PagedTiles(
                    items = SeeControl.entries.toList(),
                    page = page,
                    onPage = { p, n ->
                        page = p
                        state.onPageTurned(p, n)
                    },
                    cols = 4,
                    rows = 2,
                    onSpeak = state::say,
                    header = { m -> SpokenText(status, m, state::say, spoken = spokenStatus, maxSp = 22f) },
                ) { control, m, pos ->
                    when (control) {
                        SeeControl.READ -> Tile(
                            if (reading) "Reading…" else "Read", m,
                            spoken = "Read the text aloud",
                            icon = TileIcon.Vector(Icons.Filled.PlayArrow),
                            emphasized = true,
                            onLongPress = longPress(state, "Read the text aloud", pos),
                        ) {
                            if (!reading && granted) {
                                reading = true
                                state.say("Reading. Hold still.")
                                val still = frozen
                                scope.launch {
                                    val picture = still ?: see.photo() ?: see.screenPicture()
                                    val result = picture?.let { pic ->
                                        runCatching {
                                            withContext(Dispatchers.Default) { state.ocr().read(pic.toRgbImage()) }
                                        }.getOrNull()
                                    }
                                    reading = false
                                    if (result == null) state.say("Sorry, the text could not be read. Try again.")
                                    else state.showRead(result)
                                }
                            }
                        }
                        SeeControl.CLOSER -> Tile(
                            "Closer", m, spoken = "Zoom closer",
                            onLongPress = longPress(state, "Closer", pos),
                        ) {
                            if (frozen != null) {
                                val next = minOf(CameraMath.FROZEN_MAX_ZOOM, frozenScale * CameraMath.ZOOM_STEP)
                                if (next == frozenScale) state.say("This is the closest") else {
                                    frozenScale = next
                                    state.say(CameraMath.describeZoom(next))
                                }
                            } else if (see.closer()) {
                                state.say(CameraMath.describeZoom(see.zoom))
                            } else {
                                state.say("This is the closest")
                            }
                        }
                        SeeControl.WIDER -> Tile(
                            "Wider", m, spoken = "Zoom wider",
                            onLongPress = longPress(state, "Wider", pos),
                        ) {
                            if (frozen != null) {
                                val next = maxOf(1f, frozenScale / CameraMath.ZOOM_STEP)
                                if (next == frozenScale) state.say("This is the widest") else {
                                    frozenScale = next
                                    if (next <= 1f) frozenOffset = Offset.Zero
                                    state.say(CameraMath.describeZoom(next))
                                }
                            } else if (see.wider()) {
                                state.say(CameraMath.describeZoom(see.zoom))
                            } else {
                                state.say("This is the widest")
                            }
                        }
                        SeeControl.FREEZE -> Tile(
                            if (frozen != null) "Live" else "Freeze", m,
                            spoken = if (frozen != null) "Back to the live picture" else "Freeze the picture",
                            icon = if (frozen != null) TileIcon.Vector(Icons.Filled.PlayArrow) else null,
                            emphasized = frozen != null,
                            onLongPress = longPress(state, if (frozen != null) "Live" else "Freeze", pos),
                        ) {
                            if (frozen != null) {
                                see.live()
                                frozenScale = 1f
                                frozenOffset = Offset.Zero
                                state.say("Live picture")
                            } else if (see.freeze()) {
                                state.say("Frozen. Tap Closer to look closer, or Live to go back.")
                            } else {
                                state.say("Could not freeze the picture")
                            }
                        }
                        SeeControl.LIGHT -> Tile(
                            see.light.label, m, icon = TileIcon.Drawable(R.drawable.ic_torch),
                            emphasized = see.light != CameraMath.Light.OFF,
                            onLongPress = longPress(state, see.light.label, pos),
                        ) {
                            see.nextLight()
                            if (see.light == CameraMath.Light.TORCH && !see.hasFlash) {
                                state.say("This phone has no torch. The picture is brightened instead.")
                            } else {
                                state.say(see.light.label)
                            }
                        }
                        SeeControl.COLOURS -> Tile(
                            see.filter.label, m,
                            spoken = "Colours: ${see.filter.label}",
                            onLongPress = longPress(state, see.filter.label, pos),
                        ) {
                            see.nextFilter()
                            state.say(see.filter.label)
                        }
                        SeeControl.HOME -> Tile(
                            "Home", m, icon = TileIcon.Vector(Icons.Filled.Home),
                            onLongPress = longPress(state, "Home", pos),
                        ) { state.go(Screen.Home) }
                    }
                }
            }
        }
    }
}
