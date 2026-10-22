package dev.kura.scan.ui.item

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.kura.core.config.KuraConfig
import dev.kura.scan.R
import dev.kura.scan.ui.common.Banner
import dev.kura.scan.ui.common.BannerView
import dev.kura.scan.ui.common.InputDialog
import dev.kura.scan.ui.common.signed
import dev.kura.scan.ui.scan.ItemPaneState
import dev.kura.scan.ui.theme.deltaColor
import dev.kura.scan.ui.theme.successColor

/**
 * Fiche article. En haut, ce qui se lit (nom, champs, photo) et qui peut défiler ;
 * en bas, toujours visible, ce qui s'agit : stock actuel → stock après, pas +/−, Valider.
 */
@Composable
fun ItemPane(
    state: ItemPaneState,
    config: KuraConfig?,
    onStep: (Int) -> Unit,
    onQuantity: (Long?) -> Unit,
    onReset: () -> Unit,
    onValidate: () -> Unit,
    onScanNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(state, onClose)
            when {
                state.loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text(stringResource(R.string.item_loading), style = MaterialTheme.typography.bodyLarge)
                }
                state.notFound -> Notice(stringResource(R.string.item_not_found, state.code.itemId), error = true)
                state.offline && state.fromCache -> Notice(stringResource(R.string.item_offline_cache), error = false)
                state.offline -> Notice(stringResource(R.string.item_offline_nocache), error = false)
            }
            FieldGrid(state.fields.filter { it.key != "quantity" && it.key != "name" }.map { (it.label ?: defaultLabel(it.key)) to it.value })
        }
        ActionDock(state, config, onStep, onQuantity, onReset, onValidate, onScanNext)
    }
}

@Composable
private fun Header(state: ItemPaneState, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.imageUrl != null) {
            val context = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(state.imageUrl)
                    .apply { state.imageHeaders.forEach { (k, v) -> addHeader(k, v) } }
                    .size(320) // vignette : borne la mémoire
                    .build(),
                contentDescription = stringResource(R.string.item_image_cd),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            Text(
                state.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (state.title != state.code.itemId) {
                Text(state.code.itemId, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.item_close))
        }
    }
}

/** Champs de l'article sur deux colonnes : se lit d'un coup d'œil, sans défiler. */
@Composable
private fun FieldGrid(fields: List<Pair<String, String>>) {
    if (fields.isEmpty()) return
    Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        fields.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ActionDock(
    state: ItemPaneState,
    config: KuraConfig?,
    onStep: (Int) -> Unit,
    onQuantity: (Long?) -> Unit,
    onReset: () -> Unit,
    onValidate: () -> Unit,
    onScanNext: () -> Unit,
) {
    val stepper = state.stepper
    val allowSet = config?.display?.allowSet != false
    var counting by rememberSaveable(state.code.raw) { mutableStateOf(false) }
    // Envoyé (ou mis en file) : on propose d'enchaîner ; une erreur reste affichée au-dessus de Valider.
    val done = state.result != null && state.result.kind != Banner.Kind.ERROR && !stepper.hasChanges

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StockLine(state, config, onReset)

            // Une seule rangée « −10 −1 +1 +10 » tant qu'il y a au plus deux pas ; sinon deux rangées.
            val steps = state.stepButtons.sorted()
            val minus = steps.reversed().map { -it }
            val plus = steps
            if (steps.size <= 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (minus + plus).forEach { StepButton(it, onStep, Modifier.weight(1f)) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { minus.forEach { StepButton(it, onStep, Modifier.weight(1f)) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { plus.forEach { StepButton(it, onStep, Modifier.weight(1f)) } }
            }

            state.result?.takeIf { !done }?.let { BannerView(it, onTimeout = {}) }

            if (done) {
                DoneRow(state.result!!, onScanNext)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (allowSet) {
                        OutlinedButton(
                            onClick = { counting = true },
                            enabled = !state.submitting,
                            modifier = Modifier.heightIn(min = 56.dp),
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.item_inventory))
                        }
                    }
                    ValidateButton(state, onValidate, Modifier.weight(1f))
                }
            }
        }
    }

    if (counting) {
        InputDialog(
            title = stringResource(R.string.item_inventory_title),
            body = stringResource(R.string.item_inventory_body),
            label = stringResource(R.string.item_inventory_label),
            confirmText = stringResource(R.string.ok),
            numeric = true,
            initial = stepper.setTarget?.toString().orEmpty(),
            validate = { v -> v.trim().let { it.isNotEmpty() && it.length <= 9 && it.all(Char::isDigit) } },
            onConfirm = { v ->
                v.trim().toLongOrNull()?.let { onQuantity(it) }
                counting = false
            },
            onDismiss = { counting = false },
        )
    }
}

/** « Stock 32 → 31 » avec le mouvement en couleur : on voit tout de suite ce qui va être envoyé. */
@Composable
private fun StockLine(state: ItemPaneState, config: KuraConfig?, onReset: () -> Unit) {
    val stepper = state.stepper
    val label = config?.display?.fieldLabels?.get("quantity") ?: stringResource(R.string.field_quantity)
    val unknown = stringResource(R.string.item_quantity_unknown)
    val base = when {
        state.loading -> "…"
        else -> stepper.baseQuantity?.toString() ?: "?"
    }
    val delta = when {
        stepper.isSetMode -> (stepper.setTarget ?: 0) - (stepper.baseQuantity ?: stepper.setTarget ?: 0)
        else -> stepper.pendingDelta
    }
    val accent = deltaColor(delta)
    val after = stepper.projectedQuantity?.toString() ?: unknown
    val spoken = if (stepper.hasChanges) {
        stringResource(R.string.item_stock_cd_change, label, stepper.baseQuantity?.toString() ?: unknown, after)
    } else {
        stringResource(R.string.item_stock_cd, label, stepper.baseQuantity?.toString() ?: unknown)
    }

    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = spoken
            liveRegion = LiveRegionMode.Polite
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    base,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (stepper.hasChanges) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                val unit = state.item?.fields?.get("unit")
                if (!stepper.hasChanges && unit != null) {
                    Text(
                        " $unit",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (stepper.hasChanges) {
                    Text(
                        "  →  ",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(after, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = accent)
                }
            }
        }
        if (stepper.hasChanges) {
            Surface(color = accent.copy(alpha = 0.14f), contentColor = accent, shape = RoundedCornerShape(50)) {
                Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // Inventaire : on affiche l'écart avec le stock connu, comme pour un mouvement.
                        if (stepper.isSetMode && stepper.baseQuantity == null) stringResource(R.string.item_inventory) else signed(delta),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = onReset) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.item_reset_cd))
                    }
                }
            }
        }
    }
}

@Composable
private fun StepButton(amount: Int, onStep: (Int) -> Unit, modifier: Modifier) {
    val color = deltaColor(amount.toLong())
    val cd = stringResource(if (amount < 0) R.string.item_step_minus_cd else R.string.item_step_plus_cd, kotlin.math.abs(amount))
    FilledTonalButton(
        onClick = { onStep(amount) },
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = color.copy(alpha = 0.14f), contentColor = color),
        contentPadding = PaddingValues(horizontal = 4.dp),
        modifier = modifier.heightIn(min = 60.dp).semantics { contentDescription = cd },
    ) {
        if (amount < 0) Icon(painterResource(R.drawable.ic_remove), contentDescription = null, modifier = Modifier.size(20.dp))
        else Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(kotlin.math.abs(amount).toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ValidateButton(state: ItemPaneState, onValidate: () -> Unit, modifier: Modifier) {
    val stepper = state.stepper
    val label = when {
        !stepper.hasChanges -> stringResource(R.string.item_validate)
        stepper.isSetMode -> stringResource(R.string.item_validate_set, stepper.setTarget ?: 0)
        else -> stringResource(R.string.item_validate_delta, signed(stepper.pendingDelta))
    }
    Button(
        onClick = onValidate,
        enabled = !state.submitting && stepper.hasChanges,
        modifier = modifier.heightIn(min = 56.dp),
    ) {
        if (state.submitting) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Après un envoi réussi : confirmation et enchaînement direct sur l'article suivant. */
@Composable
private fun DoneRow(result: Banner, onScanNext: () -> Unit) {
    val ok = if (result.kind == Banner.Kind.SUCCESS) successColor() else MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(if (result.kind == Banner.Kind.SUCCESS) Icons.Filled.CheckCircle else Icons.Filled.Info, contentDescription = null, tint = ok)
            Text(result.text.resolve(), style = MaterialTheme.typography.titleMedium, color = ok, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        Button(onClick = onScanNext, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Icon(painterResource(R.drawable.ic_qr_scan), contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.item_scan_next), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Notice(text: String, error: Boolean) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite })
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
