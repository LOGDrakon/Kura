package dev.kura.scan.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.kura.scan.R
import dev.kura.scan.ui.theme.successColor
import kotlinx.coroutines.delay

/** Bandeau de résultat, annoncé par TalkBack (région « live »), qui s'efface seul. */
@Composable
fun BannerView(banner: Banner?, onTimeout: (Long) -> Unit, modifier: Modifier = Modifier) {
    if (banner == null) return
    LaunchedEffect(banner.id) {
        delay(if (banner.kind == Banner.Kind.ERROR) 5_000 else 3_000)
        onTimeout(banner.id)
    }
    val (container, content) = when (banner.kind) {
        Banner.Kind.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        Banner.Kind.SUCCESS -> successColor() to MaterialTheme.colorScheme.surface
        Banner.Kind.INFO -> MaterialTheme.colorScheme.inverseSurface to MaterialTheme.colorScheme.inverseOnSurface
    }
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = if (banner.kind == Banner.Kind.ERROR) LiveRegionMode.Assertive else LiveRegionMode.Polite },
    ) {
        Text(
            banner.text.resolve(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** Boîte de dialogue avec un champ (mot de passe, PIN, identifiant…). */
@Composable
fun InputDialog(
    title: String,
    label: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    body: String? = null,
    error: String? = null,
    busy: Boolean = false,
    password: Boolean = false,
    numeric: Boolean = false,
    initial: String = "",
    validate: (String) -> Boolean = { it.isNotBlank() },
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                body?.let { Text(it) }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null,
                    supportingText = when {
                        error != null -> { { Text(error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) } }
                        busy -> { { Text(stringResource(R.string.settings_checking)) } }
                        else -> null
                    },
                    visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when {
                            numeric && password -> KeyboardType.NumberPassword
                            numeric -> KeyboardType.Number
                            password -> KeyboardType.Password
                            else -> KeyboardType.Text
                        },
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (validate(value) && !busy) onConfirm(value) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = validate(value) && !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
fun LabelValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(0.6f))
    }
}
