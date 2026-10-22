package dev.kura.scan.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kura.core.config.AuthType
import dev.kura.core.config.TokenRef
import dev.kura.scan.BuildConfig
import dev.kura.scan.R
import dev.kura.scan.ui.common.InputDialog
import dev.kura.scan.ui.common.LabelValue
import dev.kura.scan.ui.common.SectionTitle

@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onLoadFile: () -> Unit,
    onScanQr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val transient by vm.transient.collectAsStateWithLifecycle()
    val hasSecret by vm.hasSecret.collectAsStateWithLifecycle()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .widthIn(max = 640.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })

        transient.message?.let {
            Text(it.resolve(), color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            LaunchedEffect(it) {
                kotlinx.coroutines.delay(4000)
                vm.consumeMessage()
            }
        }

        if (state.locked) {
            LockedCard(busy = transient.unlockBusy, wrong = transient.unlockWrong, onUnlock = vm::unlock)
            Text(stringResource(R.string.settings_about, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)
        } else {
            UnlockedContent(vm, state, hasSecret, onLoadFile, onScanQr)
        }
    }
}

@Composable
private fun UnlockedContent(
    vm: SettingsViewModel,
    state: SettingsUiState,
    hasSecret: Boolean,
    onLoadFile: () -> Unit,
    onScanQr: () -> Unit,
) {
        val config = state.config
        SectionTitle(stringResource(R.string.settings_server))
        if (config == null) {
            Text(stringResource(R.string.settings_no_config))
        } else {
            config.company?.let { LabelValue(stringResource(R.string.settings_company), it) }
            LabelValue(stringResource(R.string.settings_server), config.server.baseUrl)
        }

        if (config != null) {
            SectionTitle(stringResource(R.string.settings_auth))
            val auth = config.server.auth
            when {
                auth.type == AuthType.NONE -> Text(stringResource(R.string.settings_auth_none))
                auth.tokenRef == TokenRef.MDM -> Text(stringResource(R.string.settings_secret_mdm))
                else -> {
                    val label = when (auth.type) {
                        AuthType.BASIC -> stringResource(R.string.settings_secret_basic, auth.username.orEmpty())
                        AuthType.API_KEY -> stringResource(R.string.settings_secret_api_key, auth.header)
                        else -> stringResource(R.string.settings_secret_bearer)
                    }
                    Text(stringResource(if (hasSecret) R.string.settings_secret_set else R.string.settings_secret_missing))
                    var secret by rememberSaveable { mutableStateOf("") }
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = { Text(label) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { vm.saveSecret(secret); secret = "" },
                        enabled = secret.isNotBlank(),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.save)) }
                    if (hasSecret) {
                        TextButton(onClick = vm::clearSecret, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.settings_secret_clear))
                        }
                    }
                }
            }
        }

        SectionTitle(stringResource(R.string.settings_identity))
        EditableValue(stringResource(R.string.settings_device_id), state.deviceId, vm::setDeviceId)
        EditableValue(stringResource(R.string.settings_user), state.user.orEmpty(), vm::setUser)

        SectionTitle(stringResource(R.string.settings_config))
        OutlinedButton(onClick = onLoadFile, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.settings_load_file))
        }
        OutlinedButton(onClick = onScanQr, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.settings_scan_qr))
        }
        if (state.warnings.isNotEmpty()) {
            Text(stringResource(R.string.settings_warnings), style = MaterialTheme.typography.titleSmall)
            state.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }

        SectionTitle(stringResource(R.string.settings_lock))
        var settingPassword by rememberSaveable { mutableStateOf(false) }
        when {
            state.protectedByConfig -> Text(stringResource(R.string.settings_lock_from_config))
            state.hasLocalLock -> TextButton(onClick = vm::removeLocalPassword, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.settings_lock_remove))
            }
            else -> OutlinedButton(onClick = { settingPassword = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.settings_lock_set))
            }
        }
        if (state.protectedByConfig || state.hasLocalLock) {
            TextButton(onClick = vm::relock, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_relock)) }
        }
        if (settingPassword) {
            InputDialog(
                title = stringResource(R.string.settings_lock_set),
                label = stringResource(R.string.settings_lock_new),
                confirmText = stringResource(R.string.save),
                password = true,
                validate = { it.length >= 8 },
                onConfirm = { vm.setLocalPassword(it); settingPassword = false },
                onDismiss = { settingPassword = false },
            )
        }

        Text(
            stringResource(R.string.settings_about, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 24.dp),
        )
}

@Composable
private fun LockedCard(busy: Boolean, wrong: Boolean, onUnlock: (String) -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    Text(stringResource(R.string.settings_locked), style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(stringResource(R.string.settings_password)) },
        singleLine = true,
        enabled = !busy,
        isError = wrong,
        supportingText = when {
            wrong -> { { Text(stringResource(R.string.settings_wrong_password), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) } }
            busy -> { { Text(stringResource(R.string.settings_checking)) } }
            else -> null
        },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onUnlock(password) }, enabled = password.isNotEmpty() && !busy, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.settings_unlock))
    }
}

@Composable
private fun EditableValue(label: String, value: String, onSave: (String) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        LabelValue(label, value.ifEmpty { "—" })
    }
    if (editing) {
        InputDialog(
            title = label,
            label = label,
            confirmText = stringResource(R.string.save),
            initial = value,
            validate = { true },
            onConfirm = { onSave(it); editing = false },
            onDismiss = { editing = false },
        )
    }
}
