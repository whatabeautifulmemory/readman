package io.github.whatabeautifulmemory.readman

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropLandscape
import androidx.compose.material.icons.filled.CropPortrait
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import android.graphics.RectF
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Contact field → label resource. Lives here, not in Core, because Core has no Android. */
val FIELD_LABELS = mapOf(
    "display_name" to R.string.f_display_name, "company" to R.string.f_company, "department" to R.string.f_department,
    "job_title" to R.string.f_job_title, "phone_work" to R.string.f_phone_work, "phone_mobile" to R.string.f_phone_mobile,
    "phone_fax" to R.string.f_phone_fax, "email" to R.string.f_email, "address" to R.string.f_address,
    "website" to R.string.f_website, "note" to R.string.f_note,
)
private val PROVIDER_NOTES = mapOf(
    "deepseek" to R.string.note_deepseek, "opencode-go" to R.string.note_opencode_go, "opencodex" to R.string.note_opencodex,
    "openai-compatible" to R.string.note_openai_compatible, "ollama" to R.string.note_ollama, "groq" to R.string.note_groq,
)
private val THEMES = listOf("system" to R.string.theme_system, "light" to R.string.theme_light, "dark" to R.string.theme_dark)

private val LANGUAGES = listOf("system" to R.string.theme_system, "en" to R.string.lang_en, "ko" to R.string.lang_ko, "ja" to R.string.lang_ja)

class MainActivity : ComponentActivity() {
    /** UI language override below Android 13: wrap the base context so every stringResource/Toast follows it. */
    override fun attachBaseContext(base: Context) = super.attachBaseContext(base.localized(Settings(base).language))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: AppVm = viewModel()
            val dark = when (vm.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            val ctx = LocalContext.current
            val scheme = when {
                Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            var screen by rememberSaveable { mutableStateOf("main") }
            // Bars are transparent; only the icon tint has to follow the in-app theme choice — and
            // the viewfinder is black whatever the theme, so it always gets light icons.
            LaunchedEffect(dark, screen) {
                val style = if (dark || screen == "camera") SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            // A batch takes minutes; a locked screen would let Doze cut the in-flight calls. Lives here,
            // above when(screen), so entering the camera or settings does not drop it.
            val busy = screen == "camera" || vm.items.any { it.status == Status.PENDING || it.status == Status.RUNNING }
            val view = LocalView.current
            DisposableEffect(busy) { view.keepScreenOn = busy; onDispose { view.keepScreenOn = false } }
            MaterialTheme(colorScheme = scheme) {
                when (screen) {
                    "settings" -> { BackHandler { screen = "main" }; SettingsScreen(vm) { screen = "main" } }
                    // Restored after process death with the permission revoked (one-time grant, settings):
                    // bounce to main, whose capture button owns the request flow.
                    "camera" -> if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) screen = "main"
                        else { BackHandler { screen = "main" }; CameraScreen(vm) { screen = "main" } }
                    else -> MainScreen(vm, onSettings = { screen = "settings" }, onCamera = { screen = "camera" })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- main

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: AppVm, onSettings: () -> Unit, onCamera: () -> Unit) {
    val ctx = LocalContext.current
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) onCamera() else {
            toast(ctx, ctx.getString(R.string.toast_need_camera))
            if (!ActivityCompat.shouldShowRequestPermissionRationale(ctx as Activity, Manifest.permission.CAMERA))
                ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) vm.add(uris)
    }
    val scope = rememberCoroutineScope()
    val save: () -> Unit = { scope.launch { val n = withContext(Dispatchers.IO) { vm.saveAll() }; toast(ctx, ctx.getString(R.string.toast_saved, n)) } }
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) save() else {
            toast(ctx, ctx.getString(R.string.toast_need_contacts))
            // "Don't ask again": the system shows no dialog any more, so send the user to app settings.
            if (!ActivityCompat.shouldShowRequestPermissionRationale(ctx as Activity, Manifest.permission.WRITE_CONTACTS))
                ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
        }
    }
    var editingId by rememberSaveable { mutableStateOf(-1) }
    // Hoisted above the early return: the list leaves composition while editing and would otherwise
    // come back scrolled to the top.
    val listState = rememberLazyListState()
    vm.items.firstOrNull { it.id == editingId }?.let { item ->
        BackHandler { editingId = -1 }
        EditScreen(item, vm) { editingId = -1 }
        return
    }
    val ready = vm.items.count { it.savable }
    val hasCamera = remember { ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.app_name)) }, actions = {
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), enabled = hasCamera, onClick = {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) onCamera()
                    else askCamera.launch(Manifest.permission.CAMERA)
                }) { Icon(Icons.Filled.CameraAlt, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.btn_capture)) }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Icon(Icons.Filled.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.btn_pick)) }
            }
            if (!hasCamera) Text(stringResource(R.string.no_camera), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            Text("${vm.settings.provider.label} · ${vm.settings.model(vm.settings.providerId)}",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f), state = listState) {
                items(vm.items, key = { it.id }) { item ->
                    ItemRow(item) { editingId = item.id }
                    HorizontalDivider()
                }
            }
            if (vm.items.any { it.status == Status.SAVED })
                TextButton(onClick = { vm.clearSaved() }, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.btn_clear_saved)) }
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val fresh = vm.newCount
                OutlinedButton(modifier = Modifier.weight(1f), enabled = fresh > 0, onClick = { vm.analyzeNew() }) {
                    Text(stringResource(R.string.btn_analyze_all, fresh))
                }
                Button(modifier = Modifier.weight(1f), enabled = ready > 0 && !vm.saving, onClick = {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED) save()
                    else askContacts.launch(Manifest.permission.WRITE_CONTACTS)
                }) { Text(stringResource(R.string.btn_save, ready)) }
            }
        }
    }
}

@Composable
private fun ItemRow(item: CardItem, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        item.thumb?.let { Image(it.asImageBitmap(), null, Modifier.size(64.dp), contentScale = ContentScale.Crop) } ?: Spacer(Modifier.size(64.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.summary.ifBlank { item.uri.lastPathSegment ?: "" }, style = MaterialTheme.typography.bodyLarge)
            val err = item.error
            val sub = if (err != null) stringResource(R.string.error_prefix, LocalContext.current.errorText(err)) else stringResource(when (item.status) {
                Status.NEW -> R.string.status_new; Status.PENDING -> R.string.status_pending; Status.RUNNING -> R.string.status_running
                Status.DONE -> R.string.status_done; Status.SAVED -> R.string.status_saved; Status.ERROR -> R.string.status_error
            })
            Text(sub, style = MaterialTheme.typography.bodySmall,
                color = if (err != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (item.status == Status.RUNNING) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

/**
 * A full screen rather than a dialog: with eleven fields, the keyboard used to cover the dialog's
 * action row. Actions live in the top bar, so they stay reachable while typing, and the field column
 * pads itself above the IME the same way Settings does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditScreen(item: CardItem, vm: AppVm, onClose: () -> Unit) {
    val ctx = LocalContext.current
    // A sharper preview than the list thumbnail, so blur/glare is visible before tokens are spent.
    val preview by produceState(item.thumb, item) {
        value = withContext(Dispatchers.IO) { runCatching { decodeScaled(ctx, item.uri, 1200) }.getOrNull() } ?: item.thumb
    }
    val isNew = item.status == Status.NEW
    val inFlight = item.status == Status.PENDING || item.status == Status.RUNNING
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Text(stringResource(when {
                    item.status == Status.ERROR -> R.string.dlg_error; isNew -> R.string.dlg_new
                    item.status == Status.PENDING -> R.string.status_pending; item.status == Status.RUNNING -> R.string.status_running
                    else -> R.string.dlg_review
                }), maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.btn_close)) } },
            actions = {
                IconButton(onClick = { vm.remove(item); onClose() }) { Icon(Icons.Filled.Delete, stringResource(R.string.btn_delete)) }
                TextButton(enabled = isNew || item.status == Status.DONE || item.status == Status.ERROR,
                    onClick = { vm.analyze(item); onClose() }) { Text(stringResource(if (isNew) R.string.btn_analyze else R.string.btn_reanalyze)) }
            })
    }) { pad ->
        Column(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            preview?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().height(if (isNew) 360.dp else 200.dp), contentScale = ContentScale.Fit) }
            item.error?.let { Text(ctx.errorText(it), color = MaterialTheme.colorScheme.error) }
            if (inFlight) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            if (!isNew) CONTACT_FIELDS.forEach { f ->
                OutlinedTextField(value = item.contact[f] ?: "", onValueChange = { item.contact[f] = it; item.edited = true },
                    label = { Text(stringResource(FIELD_LABELS[f]!!)) }, modifier = Modifier.fillMaxWidth(),
                    enabled = item.status == Status.DONE)
            }
        }
    }
}

// ---------------------------------------------------------------- camera

/**
 * Stays open across shots: each shutter press writes a JPEG and queues it for analysis, so a stack
 * of cards is one pass through the viewfinder. LifecycleCameraController brings tap-to-focus and
 * pinch-to-zoom without extra code.
 */
@Composable
fun CameraScreen(vm: AppVm, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    // The manifest allows front-only devices (uses-feature camera required=false); fall back to front.
    val backCam = remember { ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA) }
    val controller = remember {
        LifecycleCameraController(ctx).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            cameraSelector = if (backCam) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
    }
    // takePicture() throws until CameraX has initialised (hundreds of ms on first entry), and the
    // future also completes on failure — so check get(), not merely completion.
    var ready by remember { mutableStateOf(false) }
    // Bind here, not in the AndroidView factory: leaving this screen must release the camera even
    // though the Activity itself stays RESUMED.
    DisposableEffect(lifecycle) {
        controller.bindToLifecycle(lifecycle)
        val init = controller.initializationFuture
        init.addListener({ ready = runCatching { init.get() }.isSuccess }, ContextCompat.getMainExecutor(ctx))
        onDispose { controller.unbind() }
    }
    var shots by rememberSaveable { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    // Leaving mid-capture aborts the request ("Camera is closed.") and loses the last card, so the
    // exits wait for the JPEG to land; Done/Close below are disabled for the same window.
    BackHandler(enabled = busy) {}
    val white = androidx.compose.ui.graphics.Color.White
    // Card-shaped guide, as fractions of the preview view, so the crop can be mapped onto the JPEG.
    // Landscape (90×54) or portrait (54×90, common on Japanese cards); centred either way, which is
    // what lets cropToFrame swap axes when the JPEG comes back transposed.
    var viewSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var portraitCard by remember { mutableStateOf(vm.settings.cardPortrait) }
    // Taller of the two overlay rows (insets included) — the portrait frame is height-bound and
    // would otherwise run under the shutter on 16:9–19.5:9 phones with 3-button nav.
    var barH by remember { mutableStateOf(0) }
    val gapPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val frame = remember(viewSize, portraitCard, barH) {
        val w = viewSize.width.toFloat(); val h = viewSize.height.toFloat()
        val aspect = if (portraitCard) 1f / CARD_ASPECT else CARD_ASPECT
        if (w == 0f || h == 0f) RectF(0f, 0f, 1f, 1f) else {
            // Portrait: keep the frame inside the band between the rows, still centred on both axes
            // (cropToFrame's transposition swap relies on that). Landscape keeps 0.7h — a symmetric
            // band there would be under 100dp tall.
            val maxH = if (h > w) minOf(h * 0.7f, h - 2f * (barH + gapPx)) else h * 0.7f
            val fw = minOf(w * 0.9f, maxH * aspect); val fh = fw / aspect
            RectF((w - fw) / 2 / w, (h - fh) / 2 / h, (w + fw) / 2 / w, (h + fh) / 2 / h)
        }
    }

    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).onSizeChanged { viewSize = it }) {
        AndroidView(factory = { c -> PreviewView(c).apply { this.controller = controller } }, modifier = Modifier.fillMaxSize())
        // Dim everything outside the frame; the frame itself is punched out with BlendMode.Clear.
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            val r = Offset(frame.left * size.width, frame.top * size.height)
            val s = Size(frame.width() * size.width, frame.height() * size.height)
            drawRect(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
            drawRoundRect(androidx.compose.ui.graphics.Color.Transparent, r, s, CornerRadius(12.dp.toPx()), blendMode = BlendMode.Clear)
            drawRoundRect(white, r, s, CornerRadius(12.dp.toPx()), style = Stroke(2.dp.toPx()))
        }
        // safeDrawingPadding: in landscape the punch-hole/side nav bar sits on the edge the toggle is on.
        Row(Modifier.align(Alignment.TopCenter).onSizeChanged { barH = maxOf(barH, it.height) }.safeDrawingPadding().padding(horizontal = 8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.cam_hint), Modifier.weight(1f).padding(8.dp),
                color = white, style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = { portraitCard = !portraitCard; vm.settings.cardPortrait = portraitCard },
                colors = IconButtonDefaults.iconButtonColors(contentColor = white)) {
                // Shows the orientation you would switch TO.
                if (portraitCard) Icon(Icons.Filled.CropLandscape, stringResource(R.string.cam_frame_landscape))
                else Icon(Icons.Filled.CropPortrait, stringResource(R.string.cam_frame_portrait))
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).onSizeChanged { barH = maxOf(barH, it.height) }.safeDrawingPadding().padding(24.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = !busy, onClick = onDone,
                colors = IconButtonDefaults.iconButtonColors(contentColor = white, disabledContentColor = white.copy(alpha = .38f))) {
                Icon(Icons.Filled.Close, stringResource(R.string.btn_close))
            }
            FilledIconButton(enabled = ready && !busy, modifier = Modifier.size(76.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = white, disabledContainerColor = white.copy(alpha = .5f)),
                onClick = {
                    val f = File(ctx.cacheDir, "cam").apply { mkdirs() }.resolve("${System.currentTimeMillis()}.jpg")
                    // Front lens output is EXIF-mirrored by default, which would hand the LLM mirrored text.
                    val opts = ImageCapture.OutputFileOptions.Builder(f)
                        .setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = false }).build()
                    try {
                        busy = true
                        controller.takePicture(opts, ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                                vm.addCapture(f, frame, viewSize.width.toFloat() / viewSize.height) { shots++; busy = false }
                            }
                            override fun onError(e: ImageCaptureException) {
                                toast(ctx, ctx.getString(R.string.toast_capture_failed, e.message)); busy = false
                            }
                        })
                    } catch (e: IllegalStateException) { // "Camera not initialized." — belt and braces over `ready`
                        busy = false; toast(ctx, ctx.getString(R.string.toast_capture_failed, e.message))
                    }
                }) {
                if (busy || !ready) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = androidx.compose.ui.graphics.Color.Black)
                else Icon(Icons.Filled.CameraAlt, stringResource(R.string.cam_shutter), Modifier.size(36.dp), tint = androidx.compose.ui.graphics.Color.Black)
            }
            TextButton(enabled = !busy, onClick = onDone,
                colors = ButtonDefaults.textButtonColors(contentColor = white, disabledContentColor = white.copy(alpha = .38f))) {
                Text(stringResource(R.string.cam_done, shots))
            }
        }
    }
}

// ---------------------------------------------------------------- settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppVm, onBack: () -> Unit) {
    val s = vm.settings
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var providerId by remember { mutableStateOf(s.providerId) }
    val p = providerById(providerId)
    var key by remember(providerId) { mutableStateOf(s.key(providerId)) }
    var endpoint by remember(providerId) { mutableStateOf(s.endpoint(providerId)) }
    var model by remember(providerId) { mutableStateOf(s.model(providerId)) }
    var models by rememberSaveable(providerId) { mutableStateOf(p.models) }
    var modelsOpen by remember { mutableStateOf(false) }
    var provOpen by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf(s.systemPrompt) }
    var llmMapping by remember { mutableStateOf(s.llmMapping) }
    val templates = remember { CONTACT_FIELDS.associateWith { mutableStateOf(s.template(it)) } }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        })
    }) { pad ->
        // consumeWindowInsets(pad) so imePadding() does not re-add the nav bar Scaffold already padded.
        Column(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.section_llm), style = MaterialTheme.typography.titleMedium)
            ExposedDropdownMenuBox(expanded = provOpen, onExpandedChange = { provOpen = it }) {
                OutlinedTextField(value = p.label, onValueChange = {}, readOnly = true, label = { Text(stringResource(R.string.label_service)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(provOpen) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
                ExposedDropdownMenu(expanded = provOpen, onDismissRequest = { provOpen = false }) {
                    PROVIDERS.forEach { pr ->
                        DropdownMenuItem(text = { Text(pr.label) }, onClick = {
                            providerId = pr.id; s.providerId = pr.id; provOpen = false
                        })
                    }
                }
            }
            PROVIDER_NOTES[providerId]?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
            if (p.endpointEditable) OutlinedTextField(value = endpoint, onValueChange = { endpoint = it; s.setEndpoint(providerId, it) },
                label = { Text(stringResource(R.string.label_endpoint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = key, onValueChange = { key = it; s.setKey(providerId, it) },
                label = { Text(stringResource(if (p.keyOptional) R.string.label_key_optional else R.string.label_key)) },
                placeholder = { Text(p.keyHint) },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposedDropdownMenuBox(expanded = modelsOpen, onExpandedChange = { modelsOpen = it && models.isNotEmpty() }, modifier = Modifier.weight(1f)) {
                    OutlinedTextField(value = model, onValueChange = { model = it; s.setModel(providerId, it) },
                        label = { Text(stringResource(R.string.label_model)) }, singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelsOpen) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable))
                    ExposedDropdownMenu(expanded = modelsOpen, onDismissRequest = { modelsOpen = false }) {
                        models.forEach { m ->
                            DropdownMenuItem(text = { Text(m) }, onClick = { model = m; s.setModel(providerId, m); modelsOpen = false })
                        }
                    }
                }
                OutlinedButton(enabled = !loading, onClick = {
                    loading = true
                    scope.launch {
                        try {
                            // Same trimmed/defaulted values analyze() uses, not the raw field state.
                            val list = withContext(Dispatchers.IO) { LlmClient().listModels(p, s.endpoint(providerId), s.key(providerId)) }
                            models = list; modelsOpen = list.isNotEmpty()
                            if (list.isEmpty()) toast(ctx, ctx.getString(R.string.toast_no_models))
                        } catch (e: Exception) { if (e !is CancellationException) toast(ctx, ctx.getString(R.string.toast_list_failed, ctx.errorText(e))) }
                        loading = false
                    }
                }) { if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.btn_fetch_models)) }
            }

            HorizontalDivider()
            Text(stringResource(R.string.section_mapping), style = MaterialTheme.typography.titleMedium)
            listOf(false to R.string.mode_template, true to R.string.mode_llm).forEach { (v, label) ->
                Row(Modifier.fillMaxWidth().clickable { llmMapping = v; s.llmMapping = v; vm.remap() }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = llmMapping == v, onClick = { llmMapping = v; s.llmMapping = v; vm.remap() })
                    Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!llmMapping) {
                Text(stringResource(R.string.hint_placeholders, CARD_FIELDS.joinToString(" ") { "{$it}" }), style = MaterialTheme.typography.bodySmall)
                CONTACT_FIELDS.forEach { f ->
                    val st = templates[f]!!
                    OutlinedTextField(value = st.value, onValueChange = { st.value = it; s.setTemplate(f, it); vm.remap() },
                        label = { Text(stringResource(FIELD_LABELS[f]!!)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            } else Text(stringResource(R.string.hint_llm_mode) + LLM_MAPPING_SUFFIX, style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.section_prompt), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { prompt = DEFAULT_SYSTEM_PROMPT; s.systemPrompt = prompt }) { Text(stringResource(R.string.btn_reset_prompt)) }
            }
            OutlinedTextField(value = prompt, onValueChange = { prompt = it; s.systemPrompt = it },
                modifier = Modifier.fillMaxWidth(), minLines = 8)
            Text(stringResource(R.string.hint_prompt_keys), style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()
            Text(stringResource(R.string.section_language), style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                LANGUAGES.forEachIndexed { i, (id, label) ->
                    SegmentedButton(selected = s.language == id, shape = SegmentedButtonDefaults.itemShape(i, LANGUAGES.size),
                        // Below 13 recreate() re-runs attachBaseContext (`screen` is rememberSaveable, so we land
                        // back here); on 13+ LocaleManager triggers the configuration change itself.
                        onClick = { if (s.language != id) { s.language = id; if (Build.VERSION.SDK_INT < 33) (ctx as Activity).recreate() } }) { Text(stringResource(label)) }
                }
            }
            Text(stringResource(R.string.section_appearance), style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                THEMES.forEachIndexed { i, (id, label) ->
                    SegmentedButton(selected = vm.theme == id, onClick = { vm.chooseTheme(id) },
                        shape = SegmentedButtonDefaults.itemShape(i, THEMES.size)) { Text(stringResource(label)) }
                }
            }
        }
    }
}

private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
