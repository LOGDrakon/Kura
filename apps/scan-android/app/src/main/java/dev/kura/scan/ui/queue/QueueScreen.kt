package dev.kura.scan.ui.queue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kura.scan.R
import dev.kura.scan.data.db.QueuedOpEntity
import dev.kura.scan.ui.common.signed

/** File d'attente : opérations non envoyées ou refusées, relancer / supprimer. */
@Composable
fun QueueScreen(vm: QueueViewModel, onCleartextRequired: (String) -> Unit, modifier: Modifier = Modifier) {
    val items by vm.items.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val authFailed by vm.authFailed.collectAsStateWithLifecycle()
    var toDelete by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.queue_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 12.dp).semantics { heading() },
        )
        Button(
            onClick = { vm.cleartextToConfirm()?.let(onCleartextRequired) ?: vm.sendNow() },
            enabled = !sending && items.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text(stringResource(R.string.queue_send_now)) }
        if (vm.manualMode) {
            Text(stringResource(R.string.queue_manual_mode), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
        if (authFailed) {
            Text(
                stringResource(R.string.queue_auth_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        if (items.isEmpty()) {
            Text(stringResource(R.string.queue_empty), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 24.dp))
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            items(items, key = { it.opId }) { entity ->
                QueueRow(entity, onRetry = { vm.retry(entity.opId) }, onDelete = { toDelete = entity.opId })
            }
        }
    }

    toDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            text = { Text(stringResource(R.string.queue_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(id); toDelete = null }) { Text(stringResource(R.string.queue_delete)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun QueueRow(e: QueuedOpEntity, onRetry: () -> Unit, onDelete: () -> Unit) {
    val isError = e.state == "ERROR"
    val name = e.label ?: e.itemId
    Card(
        colors = if (isError) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                val op = if (e.type == "set") stringResource(R.string.queue_op_set, name, e.value.toInt())
                else stringResource(R.string.queue_op_delta, name, signed(e.value))
                Text(op, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (isError) R.string.queue_error else R.string.queue_pending) +
                        (e.errorReason?.let { " · " + reasonLabel(it) } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
                e.lastMessage?.let {
                    Text(stringResource(R.string.queue_attempts, e.attempts, it), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (isError) {
                IconButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.queue_retry_cd, name))
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.queue_delete_cd, name))
            }
        }
    }
}

@Composable
private fun reasonLabel(reason: String): String = when (reason) {
    "invalid" -> stringResource(R.string.reason_invalid)
    "unknown_item" -> stringResource(R.string.reason_unknown_item)
    "forbidden" -> stringResource(R.string.reason_forbidden)
    "conflict" -> stringResource(R.string.reason_conflict)
    "server_error" -> stringResource(R.string.reason_server_error)
    else -> reason
}
