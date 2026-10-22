package dev.kura.scan.ui.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kura.core.config.Direction
import dev.kura.core.config.KuraConfig
import dev.kura.core.scan.Symbology
import dev.kura.scan.R
import dev.kura.scan.scan.CameraPreview
import dev.kura.scan.scan.ZxingAnalyzer
import dev.kura.scan.ui.common.BannerView
import dev.kura.scan.ui.item.ItemPane
import dev.kura.scan.ui.theme.successColor

/**
 * Écran Scan. [twoPane] : écran large (tablette, paysage) → fiche ou session à côté de la caméra.
 * Sans config ([KuraConfig] absente), l'écran sert à lire le QR de configuration (premier lancement).
 */
@Composable
fun ScanScreen(
    vm: ScanViewModel,
    twoPane: Boolean,
    onLoadFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(vm) {
        vm.screenActive = true
        onDispose { vm.screenActive = false }
    }

    val item = state.item
    if (!twoPane && item != null) {
        // Téléphone : la fiche prend l'écran ; retour = revenir à la visée.
        BackHandler { vm.closeItem() }
        ItemPane(
            state = item,
            config = state.config,
            onStep = vm::onStep,
            onQuantity = vm::onQuantityInput,
            onValidate = vm::onValidate,
            onClose = vm::closeItem,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    if (twoPane) {
        Row(modifier.fillMaxSize()) {
            ScanColumn(state, vm, onLoadFile, showSidePanelInline = false, modifier = Modifier.weight(1f).fillMaxHeight())
            Box(Modifier.weight(1f).widthIn(max = 520.dp).fillMaxHeight()) {
                when {
                    state.config == null -> Unit
                    state.mode == ScanMode.BURST -> BurstPanel(state, vm, Modifier.fillMaxSize().padding(16.dp))
                    item != null -> ItemPane(
                        state = item,
                        config = state.config,
                        onStep = vm::onStep,
                        onQuantity = vm::onQuantityInput,
                        onValidate = vm::onValidate,
                        onClose = vm::closeItem,
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> Text(
                        stringResource(R.string.item_empty_pane),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                }
            }
        }
    } else {
        ScanColumn(state, vm, onLoadFile, showSidePanelInline = true, modifier = modifier.fillMaxSize())
    }

    state.cleartextPrompt?.let { url ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = vm::dismissCleartext,
            title = { Text(stringResource(R.string.cleartext_title)) },
            text = { Text(stringResource(R.string.cleartext_body, url)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = vm::acceptCleartext) { Text(stringResource(R.string.cleartext_accept)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = vm::dismissCleartext) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ScanColumn(
    state: ScanUiState,
    vm: ScanViewModel,
    onLoadFile: () -> Unit,
    showSidePanelInline: Boolean,
    modifier: Modifier = Modifier,
) {
    val config = state.config
    Column(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(Modifier.height(4.dp))
        if (config == null) {
            OnboardingHeader(onLoadFile)
        } else {
            ModeControls(state, vm)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CameraArea(state, vm, Modifier.fillMaxSize())
            BannerView(state.banner, vm::dismissBanner, Modifier.align(Alignment.TopCenter).padding(8.dp))
        }
        if (showSidePanelInline && config != null && state.mode == ScanMode.BURST) {
            BurstPanel(state, vm, Modifier.fillMaxWidth().heightIn(max = 220.dp))
        }
        ScannerButton(state, vm::onScanButton)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun OnboardingHeader(onLoadFile: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.onboarding_body), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.onboarding_hid_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onLoadFile, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.onboarding_file))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeControls(state: ScanUiState, vm: ScanViewModel) {
    val burstEnabled = state.config?.burst?.enabled != false
    if (burstEnabled) {
        val modeLabel = stringResource(R.string.mode_label)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().semantics { contentDescription = modeLabel }) {
            val modes = listOf(ScanMode.ITEM to R.string.mode_item, ScanMode.BURST to R.string.mode_burst)
            modes.forEachIndexed { i, (mode, label) ->
                SegmentedButton(
                    selected = state.mode == mode,
                    onClick = { vm.setMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(label)) }
            }
        }
    }
    if (state.mode == ScanMode.BURST) {
        val dirLabel = stringResource(R.string.direction_label)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().semantics { contentDescription = dirLabel }) {
            val dirs = listOf(Direction.IN to R.string.direction_in, Direction.OUT to R.string.direction_out)
            dirs.forEachIndexed { i, (dir, label) ->
                SegmentedButton(
                    selected = state.direction == dir,
                    onClick = { vm.setDirection(dir) },
                    shape = SegmentedButtonDefaults.itemShape(i, dirs.size),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    val sign = if (dir == Direction.IN) "+" else "−"
                    Text("${stringResource(label)} ($sign${state.burstStep})")
                }
            }
        }
    }
}

@Composable
private fun CameraArea(state: ScanUiState, vm: ScanViewModel, modifier: Modifier) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    Box(modifier.clip(MaterialTheme.shapes.large).background(Color.Black)) {
        when {
            !granted -> PermissionCard(
                denied = denied,
                onRequest = { launcher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                modifier = Modifier.align(Alignment.Center),
            )
            !state.cameraAvailable -> Text(
                stringResource(R.string.camera_unavailable),
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            else -> {
                val symbologies = state.config?.scan?.symbologies
                val aim = (state.config?.scan?.trigger?.aimArea ?: 0.6).toFloat()
                val analyzer = remember(symbologies, aim) {
                    ZxingAnalyzer(
                        // Sans config, seul le QR (de configuration) est utile.
                        symbologies = symbologies?.let { Symbology.fromSchemaNames(it) } ?: setOf(Symbology.QR_CODE),
                        aimArea = { aim },
                        isArmed = vm::isArmedForAnalyzer,
                        onCode = vm::onFrameDecoded,
                    )
                }
                CameraPreview(
                    analyzer = analyzer,
                    torchOn = state.torchOn,
                    onCapabilities = { vm.onCameraCapabilities(it.hasTorch, it.available) },
                    modifier = Modifier.fillMaxSize(),
                )
                AimOverlay(aim, state.trigger, Modifier.fillMaxSize())
                if (state.hasTorch) {
                    FilledTonalIconButton(
                        onClick = vm::toggleTorch,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).size(56.dp),
                    ) {
                        Icon(
                            painterResource(if (state.torchOn) R.drawable.ic_flash_off else R.drawable.ic_flash_on),
                            contentDescription = stringResource(if (state.torchOn) R.string.torch_off else R.string.torch_on),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(denied: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit, modifier: Modifier) {
    Card(modifier.padding(16.dp)) {
        Column(
            Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.camera_rationale_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (denied) R.string.camera_denied else R.string.camera_rationale_body))
            Button(onClick = onRequest, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.camera_allow)) }
            if (denied) {
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.camera_open_settings))
                }
            }
        }
    }
}

/** Cadre de visée : même proportion que la zone recadrée par [ZxingAnalyzer]. Vert après une lecture. */
@Composable
private fun AimOverlay(aimArea: Float, trigger: TriggerUi, modifier: Modifier) {
    val description = stringResource(R.string.scan_aim_cd)
    val primary = MaterialTheme.colorScheme.primary
    val success = successColor()
    val error = MaterialTheme.colorScheme.error
    val color = when (trigger) {
        TriggerUi.Success -> success
        TriggerUi.Failed -> error
        is TriggerUi.Armed, TriggerUi.Continuous -> primary
        TriggerUi.Idle -> Color.White
    }
    Canvas(modifier.semantics { contentDescription = description }) {
        val w = (size.width * aimArea.coerceIn(0.2f, 1f))
        val h = (w * ZxingAnalyzer.AIM_RATIO).coerceAtMost(size.height * 0.9f)
        val topLeft = Offset((size.width - w) / 2, (size.height - h) / 2)
        // Assombrit l'extérieur du cadre pour guider l'œil vers la zone lue.
        val shade = Color.Black.copy(alpha = 0.35f)
        drawRect(shade, Offset.Zero, Size(size.width, topLeft.y))
        drawRect(shade, Offset(0f, topLeft.y + h), Size(size.width, size.height - topLeft.y - h))
        drawRect(shade, Offset(0f, topLeft.y), Size(topLeft.x, h))
        drawRect(shade, Offset(topLeft.x + w, topLeft.y), Size(size.width - topLeft.x - w, h))
        drawRoundRect(
            color = color,
            topLeft = topLeft,
            size = Size(w, h),
            cornerRadius = CornerRadius(16.dp.toPx()),
            style = Stroke(width = if (trigger is TriggerUi.Armed) 5.dp.toPx() else 3.dp.toPx()),
        )
    }
}

/** Gros bouton « Scanner », en bas (à portée du pouce), au moins 72 dp de haut. */
@Composable
private fun ScannerButton(state: ScanUiState, onClick: () -> Unit) {
    val trigger = state.trigger
    val armed = trigger is TriggerUi.Armed
    val label = when (trigger) {
        is TriggerUi.Armed -> stringResource(R.string.scan_button_armed)
        TriggerUi.Continuous -> stringResource(R.string.scan_continuous)
        else -> stringResource(R.string.scan_button)
    }
    val cd = stringResource(if (armed) R.string.scan_button_armed_cd else R.string.scan_button_cd)
    Column(Modifier.fillMaxWidth()) {
        if (trigger is TriggerUi.Armed) {
            LinearProgressIndicator(progress = { trigger.remaining }, modifier = Modifier.fillMaxWidth().height(4.dp))
        }
        Button(
            onClick = onClick,
            enabled = trigger !is TriggerUi.Continuous,
            colors = if (armed) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary) else ButtonDefaults.buttonColors(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .semantics {
                    contentDescription = cd
                    stateDescription = label
                },
        ) {
            Icon(painterResource(R.drawable.ic_qr_scan), contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.size(12.dp))
            Text(label, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        }
    }
}
