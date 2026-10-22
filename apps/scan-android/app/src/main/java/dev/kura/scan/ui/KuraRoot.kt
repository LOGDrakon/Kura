package dev.kura.scan.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kura.scan.R
import dev.kura.scan.ui.common.InputDialog
import dev.kura.scan.ui.queue.QueueScreen
import dev.kura.scan.ui.queue.QueueViewModel
import dev.kura.scan.ui.scan.ScanScreen
import dev.kura.scan.ui.scan.ScanViewModel
import dev.kura.scan.ui.settings.ImportDialogs
import dev.kura.scan.ui.settings.ImportViewModel
import dev.kura.scan.ui.settings.SettingsScreen
import dev.kura.scan.ui.settings.SettingsViewModel

enum class Tab { SCAN, QUEUE, SETTINGS }

/**
 * Racine : navigation par onglets (barre en bas sur téléphone, rail sur écran large).
 * Sans config, seul l'écran de premier lancement (lecture du QR de configuration) est proposé.
 */
@Composable
fun KuraRoot(windowSize: WindowSizeClass, scanVm: ScanViewModel = viewModel()) {
    val importVm: ImportViewModel = viewModel()
    val queueVm: QueueViewModel = viewModel()
    val settingsVm: SettingsViewModel = viewModel()
    val scanState by scanVm.state.collectAsStateWithLifecycle()
    val queueCount by queueVm.count.collectAsStateWithLifecycle()
    val settingsState by settingsVm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.SCAN) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(scanVm) { scanVm.configQr.collect { importVm.onQrText(it) } }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(importVm::onFile)
    }
    val loadFile = { fileLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }

    val wide = windowSize.widthSizeClass != WindowWidthSizeClass.Compact
    val hasConfig = scanState.config != null
    val current = if (hasConfig) tab else Tab.SCAN

    val content: @Composable (Modifier) -> Unit = { mod ->
        when (current) {
            Tab.SCAN -> ScanScreen(scanVm, twoPane = wide, onLoadFile = loadFile, queueCount = queueCount, onOpenQueue = { tab = Tab.QUEUE }, modifier = mod)
            Tab.QUEUE -> QueueScreen(queueVm, onCleartextRequired = { url -> scanVm.promptCleartext(url); tab = Tab.SCAN }, modifier = mod)
            Tab.SETTINGS -> SettingsScreen(
                settingsVm,
                onLoadFile = loadFile,
                onScanQr = { tab = Tab.SCAN },
                modifier = mod,
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (hasConfig && !wide) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = current == t,
                            onClick = { tab = t },
                            icon = { TabIcon(t, queueCount) },
                            label = { Text(stringResource(t.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (hasConfig && wide) {
            Row(Modifier.fillMaxSize().padding(padding)) {
                NavigationRail {
                    Tab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = current == t,
                            onClick = { tab = t },
                            icon = { TabIcon(t, queueCount) },
                            label = { Text(stringResource(t.label)) },
                        )
                    }
                }
                content(Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) { content(Modifier.weight(1f)) }
        }
    }

    ImportDialogs(importVm)

    // Identifiant utilisateur demandé à l'ouverture si la config l'exige.
    val requireUser = scanState.config?.security?.requireUser == true
    if (requireUser && settingsState.user.isNullOrBlank()) {
        InputDialog(
            title = stringResource(R.string.user_prompt_title),
            body = stringResource(R.string.user_prompt_body),
            label = stringResource(R.string.settings_user),
            confirmText = stringResource(R.string.ok),
            onConfirm = settingsVm::setUser,
            onDismiss = {},
        )
    }
}

private val Tab.label: Int
    get() = when (this) {
        Tab.SCAN -> R.string.nav_scan
        Tab.QUEUE -> R.string.nav_queue
        Tab.SETTINGS -> R.string.nav_settings
    }

@Composable
private fun TabIcon(tab: Tab, queueCount: Int) {
    val painter: Painter = when (tab) {
        Tab.SCAN -> painterResource(R.drawable.ic_qr_scan)
        Tab.QUEUE -> rememberVectorPainter(Icons.AutoMirrored.Filled.List)
        Tab.SETTINGS -> rememberVectorPainter(Icons.Filled.Settings)
    }
    if (tab == Tab.QUEUE && queueCount > 0) {
        val cd = stringResource(R.string.queue_badge_cd, queueCount)
        BadgedBox(badge = { Badge(Modifier.semantics { contentDescription = cd }) { Text(queueCount.coerceAtMost(999).toString()) } }) {
            Icon(painter, contentDescription = null)
        }
    } else {
        Icon(painter, contentDescription = null)
    }
}
