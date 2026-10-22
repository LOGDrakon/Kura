package dev.kura.scan.ui.scan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kura.core.burst.BurstLine
import dev.kura.scan.R
import dev.kura.scan.ui.common.InputDialog
import dev.kura.scan.ui.common.signed

/** Panneau de session rafale : lignes regroupées par article, annuler, corriger une ligne (ADR 0004 §5). */
@Composable
fun BurstPanel(state: ScanUiState, vm: ScanViewModel, modifier: Modifier = Modifier) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    Card(modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.burst_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = vm::undoLast, enabled = state.canUndo) {
                    Icon(painterResource(R.drawable.ic_undo), contentDescription = stringResource(R.string.burst_undo))
                }
                TextButton(onClick = vm::newBurstSession, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.burst_new_session))
                }
            }
            if (state.burstLines.isEmpty()) {
                val step = signed(state.burstStep.toLong() * state.direction.sign)
                Text(stringResource(R.string.burst_empty, step), style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn {
                    items(state.burstLines.asReversed(), key = { it.itemId }) { line ->
                        BurstLineRow(line, onClick = { editing = line.itemId })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    editing?.let { id ->
        val line = state.burstLines.firstOrNull { it.itemId == id }
        InputDialog(
            title = stringResource(R.string.burst_edit_line, line?.label ?: id),
            label = stringResource(R.string.burst_edit_label),
            confirmText = stringResource(R.string.save),
            numeric = false,
            initial = line?.total?.toString().orEmpty(),
            validate = { it.trim().replace('−', '-').toLongOrNull() != null },
            onConfirm = {
                it.trim().replace('−', '-').toLongOrNull()?.let { total -> vm.correctLine(id, total) }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun BurstLineRow(line: BurstLine, onClick: () -> Unit) {
    val name = line.label ?: line.itemId
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = stringResource(R.string.burst_edit_line, name), onClick = onClick)
            .padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.burst_line, name, line.scans),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(signed(line.total), style = MaterialTheme.typography.titleMedium)
    }
}
