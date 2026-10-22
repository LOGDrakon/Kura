package dev.kura.scan.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kura.scan.R
import dev.kura.scan.ui.common.InputDialog

/** Étapes de l'import de config : PIN, erreurs de validation, confirmation, mot de passe de verrouillage. */
@Composable
fun ImportDialogs(vm: ImportViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val s = state) {
        ImportState.None -> Unit
        is ImportState.NeedPin -> InputDialog(
            title = stringResource(R.string.import_pin_title),
            body = stringResource(R.string.import_pin_body),
            label = stringResource(R.string.import_pin_label),
            confirmText = stringResource(R.string.ok),
            password = true,
            numeric = true,
            busy = s.busy,
            error = if (s.wrong) stringResource(R.string.import_wrong_pin) else null,
            onConfirm = vm::onPin,
            onDismiss = vm::dismiss,
        )
        is ImportState.Invalid -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text(stringResource(R.string.import_invalid_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.errors.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = { TextButton(onClick = vm::dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ok)) } },
        )
        is ImportState.Confirm -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text(stringResource(R.string.import_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.config.company?.let { Text(stringResource(R.string.import_company, it)) }
                    Text(stringResource(R.string.import_server, s.config.server.baseUrl), style = MaterialTheme.typography.titleSmall)
                    if (s.secret != null) Text(stringResource(R.string.import_with_secret))
                    s.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = vm::onConfirm, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.import_apply)) } },
            dismissButton = { TextButton(onClick = vm::dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } },
        )
        is ImportState.NeedUnlock -> InputDialog(
            title = stringResource(R.string.settings_locked),
            body = stringResource(R.string.import_locked),
            label = stringResource(R.string.settings_password),
            confirmText = stringResource(R.string.settings_unlock),
            password = true,
            busy = s.busy,
            error = if (s.wrong) stringResource(R.string.settings_wrong_password) else null,
            onConfirm = vm::onUnlock,
            onDismiss = vm::dismiss,
        )
        is ImportState.Done -> AlertDialog(
            onDismissRequest = vm::dismiss,
            text = { Text(s.message.resolve(), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
            confirmButton = { TextButton(onClick = vm::dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ok)) } },
        )
    }
}
