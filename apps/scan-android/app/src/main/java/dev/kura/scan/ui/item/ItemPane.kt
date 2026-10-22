package dev.kura.scan.ui.item

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.kura.core.config.KuraConfig
import dev.kura.scan.R
import dev.kura.scan.ui.common.BannerView
import dev.kura.scan.ui.common.LabelValue
import dev.kura.scan.ui.common.signed
import dev.kura.scan.ui.scan.ItemPaneState

/** Fiche article : champs, image optionnelle, pas +/−, quantité (set), Valider. */
@Composable
fun ItemPane(
    state: ItemPaneState,
    config: KuraConfig?,
    onStep: (Int) -> Unit,
    onQuantity: (Long?) -> Unit,
    onValidate: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                if (state.title != state.code.itemId) {
                    Text(state.code.itemId, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.item_close))
            }
        }

        when {
            state.loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.padding(4.dp))
                Text(stringResource(R.string.item_loading))
            }
            state.notFound -> Notice(stringResource(R.string.item_not_found, state.code.itemId), error = true)
            state.offline && state.fromCache -> Notice(stringResource(R.string.item_offline_cache), error = false)
            state.offline -> Notice(stringResource(R.string.item_offline_nocache), error = false)
        }

        if (state.imageUrl != null) {
            val context = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(state.imageUrl)
                    .apply { state.imageHeaders.forEach { (k, v) -> addHeader(k, v) } }
                    .size(640) // borne la mémoire, suffisant pour l'écran
                    .build(),
                contentDescription = stringResource(R.string.item_image_cd),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(MaterialTheme.shapes.medium),
            )
        }

        state.fields.filter { it.key != "quantity" }.forEach { f ->
            LabelValue(f.label ?: defaultLabel(f.key), f.value)
        }

        QuantitySection(state, config, onStep, onQuantity)

        state.result?.let { BannerView(it, onTimeout = {}) }

        Button(
            onClick = onValidate,
            enabled = !state.submitting && state.stepper.hasChanges,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        ) {
            if (state.submitting) CircularProgressIndicator(Modifier.padding(4.dp), color = MaterialTheme.colorScheme.onPrimary)
            else Text(stringResource(R.string.item_validate), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun QuantitySection(state: ItemPaneState, config: KuraConfig?, onStep: (Int) -> Unit, onQuantity: (Long?) -> Unit) {
    val stepper = state.stepper
    val base = stepper.baseQuantity?.toString() ?: stringResource(R.string.item_quantity_unknown)
    val label = config?.display?.fieldLabels?.get("quantity") ?: stringResource(R.string.field_quantity)
    LabelValue(label, base)

    // Deux rangées (− puis +) : tient sur un écran de 320 dp même avec 3 pas et une grande police.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.stepButtons.forEach { step ->
            val cd = stringResource(R.string.item_step_minus_cd, step)
            FilledTonalButton(
                onClick = { onStep(-step) },
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).semantics { this.contentDescription = cd },
            ) {
                Icon(painterResource(R.drawable.ic_remove), contentDescription = null)
                Text(step.toString(), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.stepButtons.forEach { step ->
            val cd = stringResource(R.string.item_step_plus_cd, step)
            FilledTonalButton(
                onClick = { onStep(step) },
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).semantics { this.contentDescription = cd },
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(step.toString(), style = MaterialTheme.typography.titleMedium)
            }
        }
    }

    if (config?.display?.allowSet != false) {
        var input by rememberSaveable(state.code.raw) { mutableStateOf("") }
        // Après validation, le champ se vide (l'état repasse en mode delta).
        LaunchedEffect(stepper.isSetMode) { if (!stepper.isSetMode) input = "" }
        OutlinedTextField(
            value = input,
            onValueChange = { v ->
                val digits = v.filter { it.isDigit() }.take(9)
                input = digits
                onQuantity(digits.toLongOrNull())
            },
            label = { Text(stringResource(R.string.item_set_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    val projected = stepper.projectedQuantity?.toString() ?: stringResource(R.string.item_quantity_unknown)
    val summary = if (stepper.isSetMode) stringResource(R.string.item_set_mode) + " · " + stringResource(R.string.item_quantity_after, projected)
    else if (stepper.pendingDelta != 0L) stringResource(R.string.item_pending_delta, signed(stepper.pendingDelta)) + " · " + stringResource(R.string.item_quantity_after, projected)
    else null
    summary?.let {
        Text(
            it,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun Notice(text: String, error: Boolean) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, modifier = Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun defaultLabel(key: String): String = when (key) {
    "name" -> stringResource(R.string.field_name)
    "location" -> stringResource(R.string.field_location)
    "quantity" -> stringResource(R.string.field_quantity)
    "unit" -> stringResource(R.string.field_unit)
    "ref" -> stringResource(R.string.field_ref)
    else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
