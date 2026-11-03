package com.inspect3d.viewer.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inspect3d.viewer.core.CrashLog
import com.inspect3d.viewer.model.ModelFormat
import com.inspect3d.viewer.render.ViewerRenderer
import com.inspect3d.viewer.viewmodel.InteractionMode
import com.inspect3d.viewer.viewmodel.ViewerUiState
import com.inspect3d.viewer.viewmodel.ViewerViewModel
import com.inspect3d.viewer.viewmodel.formatCount
import com.inspect3d.viewer.viewmodel.formatDimensions

/**
 * The whole viewer: a Filament `SurfaceView` under an edge-to-edge Compose overlay.
 *
 * The renderer is remembered across recompositions, tied to the composable's lifetime for
 * destruction and to the host lifecycle for pausing, and reads its state straight from the
 * ViewModel's [kotlinx.coroutines.flow.StateFlow] — so a finger drag updates the camera at
 * display rate without forcing the overlay to recompose.
 */
// Lint asks every Scaffold to consume the content padding it is handed, so that nothing
// ends up underneath a system bar. Here that is the wrong answer: the viewport is meant to
// run edge to edge, behind the bars, and the overlays that must clear them say so
// individually (statusBarsPadding on the header, navigationBarsPadding on the controls).
@Suppress("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun ViewerScreen(
    /** A model passed in by another app via ACTION_VIEW, or null. */
    incomingModelUri: Uri? = null,
    onIncomingModelHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val viewModel: ViewerViewModel = viewModel(factory = ViewerViewModel.Factory(application))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Creating the engine touches native code and the GPU driver. If that fails, show the
    // reason instead of letting the process die: a 3D viewer that vanishes on launch tells
    // the user nothing, and the message is the only clue they can act on or report.
    val rendererAttempt = remember { runCatching { ViewerRenderer(application) } }
    val renderer = rendererAttempt.getOrNull()
    if (renderer == null) {
        EngineUnavailable(rendererAttempt.exceptionOrNull())
        return
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var controlsExpanded by remember { mutableStateOf(false) }

    DisposableEffect(renderer) {
        renderer.stateProvider = { viewModel.uiState.value }
        renderer.onNotice = { viewModel.showNotice(it) }
        renderer.onViewportResized = viewModel::onViewportChanged
        onDispose {
            renderer.stateProvider = null
            renderer.onNotice = null
            renderer.onViewportResized = null
            renderer.destroy()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, renderer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> renderer.resume()
                Lifecycle.Event.ON_PAUSE -> renderer.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // A file manager (or `adb shell am start -a android.intent.action.VIEW`) can hand the
    // app a model directly; the manifest advertises that, so it has to work.
    LaunchedEffect(incomingModelUri) {
        incomingModelUri?.let { uri ->
            viewModel.openModel(uri)
            onIncomingModelHandled()
        }
    }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::openModel) }

    val actions = remember(viewModel, openDocument) {
        ViewerActions(
            openFilePicker = { openDocument.launch(ModelFormat.PICKER_MIME_TYPES) },
            resetCamera = viewModel::resetCamera,
            setProjection = viewModel::setProjection,
            setShading = viewModel::setShading,
            setColor = viewModel::setColor,
            setMetallic = viewModel::setMetallic,
            setRoughness = viewModel::setRoughness,
            setGridVisible = viewModel::setGridVisible,
            setInteractionMode = viewModel::setInteractionMode,
            undoLastPoint = viewModel::undoLastPoint,
            clearMeasurement = viewModel::clearMeasurement,
            setSectionEnabled = viewModel::setSectionEnabled,
            setSectionOffsetX = { viewModel.setSectionOffset(x = it) },
            setSectionOffsetY = { viewModel.setSectionOffset(y = it) },
            setSectionOffsetZ = { viewModel.setSectionOffset(z = it) },
            setSectionAzimuth = viewModel::setSectionAzimuth,
            setSectionElevation = viewModel::setSectionElevation,
            setSectionFlipped = viewModel::setSectionFlipped,
            setSectionPlaneVisible = viewModel::setSectionPlaneVisible,
            resetSection = viewModel::resetSection,
        )
    }

    // If the previous run died, the trace was written on the way down. Show it once, on
    // the next launch, rather than letting the app close silently and leave nothing to go
    // on. Read once per composition tree, not on every recomposition.
    var crashReport by remember { mutableStateOf(CrashLog.pending(context)) }
    crashReport?.let { report ->
        CrashReportDialog(
            report = report,
            onDismiss = {
                CrashLog.clear(context)
                crashReport = null
            },
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        // contentColorFor(Transparent) is unspecified, which leaves text defaulting to
        // black on top of the dark viewport. Set it explicitly.
        contentColor = MaterialTheme.colorScheme.onSurface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            // ---- the 3D viewport -------------------------------------------------
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .viewportGestures(
                        onOrbit = viewModel::orbit,
                        onZoom = viewModel::zoom,
                        onPan = { dx, dy -> viewModel.pan(dx, dy, renderer.viewportHeightPixels) },
                        onTap = { position ->
                            if (state.interactionMode == InteractionMode.MEASURE) {
                                val point = renderer.pick(position.x, position.y)
                                if (point != null) {
                                    viewModel.onSurfacePicked(point)
                                } else {
                                    viewModel.onPickMissed()
                                }
                            }
                        },
                    ),
                factory = { ctx ->
                    SurfaceView(ctx).also { surfaceView ->
                        renderer.attachTo(surfaceView)
                        renderer.resume()
                    }
                },
            )

            // ---- top status ------------------------------------------------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                // Shader compilation happens on the device, so this is a real possibility
                // on an unusual driver; a persistent banner beats a snackbar that fades.
                renderer.initializationError?.let { message ->
                    ErrorBanner(message = message, modifier = Modifier.padding(bottom = 10.dp))
                }

                ModelHeader(state = state)

                AnimatedVisibility(visible = state.interactionMode == InteractionMode.MEASURE) {
                    Box(modifier = Modifier.padding(top = 10.dp)) {
                        MeasurementCard(
                            model = state.model,
                            measurement = state.measurement,
                            onUndo = actions.undoLastPoint,
                            onClear = actions.clearMeasurement,
                        )
                    }
                }
            }

            // ---- empty / loading -------------------------------------------------
            if (!state.hasModel && !state.isLoading) {
                EmptyState(
                    modifier = Modifier.align(Alignment.Center),
                    onOpen = actions.openFilePicker,
                )
            }

            if (state.isLoading) {
                LoadingOverlay(
                    fileName = state.loadingFileName,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // ---- controls + file picker ------------------------------------------
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.End,
            ) {
                // Only once something is loaded: with an empty viewport the centred
                // "Choose a file" button is already the call to action, and showing both
                // puts the same command on screen twice.
                if (state.hasModel) {
                    ExtendedFloatingActionButton(
                        onClick = actions.openFilePicker,
                        modifier = Modifier.padding(end = 16.dp, bottom = 12.dp),
                        icon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                        text = { Text("Open another") },
                    )
                }

                ControlsPanel(
                    state = state,
                    actions = actions,
                    expanded = controlsExpanded,
                    onExpandedChange = { controlsExpanded = it },
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        }
    }
}

@Composable
private fun ModelHeader(state: ViewerUiState) {
    val model = state.model ?: return
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = model.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
            Text(
                text = "${model.format.label} · ${formatCount(model.triangleCount)} triangles · " +
                    formatDimensions(model),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier, onOpen: () -> Unit) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.FolderOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = "No model open",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Open an ${ModelFormat.supportedExtensionsLabel} file from device " +
                "storage or a cloud drive to inspect, measure and section it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.size(16.dp))
        ExtendedFloatingActionButton(
            onClick = onOpen,
            icon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
            text = { Text("Choose a file") },
        )
        // Worth showing on a pre-release build: a bug report is close to useless without
        // knowing which build produced it.
        Text(
            text = "v${CrashLog.versionName(LocalContext.current)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 20.dp),
        )
    }
}

/**
 * The stack trace from a crashed previous run.
 *
 * Deliberately a modal dialog rather than a snackbar: it is the only chance to hand over
 * this information, and it is useless if it fades after four seconds. The text is
 * monospaced, scrollable and copyable, because the point is to get it off the device and
 * into a bug report.
 */
@Composable
private fun CrashReportDialog(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text("The app closed unexpectedly") },
        text = {
            Column {
                Text(
                    text = "This is the error from last time. Copying it into a bug " +
                        "report is the single most useful thing you can do with it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = report,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // The platform clipboard rather than Compose's, which has been
                    // through two API changes recently; this one does not move.
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Inspect3D crash", report))
                    onDismiss()
                },
            ) { Text("Copy and dismiss") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        },
    )
}

/**
 * Shown when the Filament engine itself could not be created — a driver or native-library
 * problem. There is no viewer to show, so this explains what happened and says what to
 * include in a report.
 */
@Composable
private fun EngineUnavailable(cause: Throwable?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.size(12.dp))
            Text(
                text = "The 3D engine could not start",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "This device's graphics driver rejected the renderer, so there is " +
                    "nothing to display. The error below is the useful part of a bug report.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.size(14.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = buildString {
                        append(cause?.javaClass?.name ?: "Unknown error")
                        cause?.message?.let { append(": ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

/** Persistent banner for a non-fatal start-up problem, such as shader compilation. */
@Composable
private fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.92f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onError,
            )
        }
    }
}

@Composable
private fun LoadingOverlay(fileName: String?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Parsing geometry…", style = MaterialTheme.typography.bodyMedium)
                if (!fileName.isNullOrBlank()) {
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
