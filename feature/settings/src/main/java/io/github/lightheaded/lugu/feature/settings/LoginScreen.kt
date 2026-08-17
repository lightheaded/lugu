package io.github.lightheaded.lugu.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Sign-in, plus the connection settings folded away underneath it.
 *
 * The folded section is not a convenience. Behind an identity-aware proxy such as
 * Cloudflare Access there is no request that succeeds without the custom headers — not
 * the address check, not the sign-in — so a client that only offers them once you are
 * signed in offers them to nobody who needs them. The same goes for a client certificate:
 * the handshake fails before there is a password prompt to reach.
 */
@Composable
fun LoginScreen(
    onSignedIn: () -> Unit,
    modifier: Modifier = Modifier,
    devServerUrl: String = "",
    devUsername: String = "",
    devPassword: String = "",
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val pickCertificate = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> viewModel.onCertificatePicked(uri) }

    LaunchedEffect(devServerUrl, devUsername) {
        if (devServerUrl.isNotBlank()) viewModel.prefill(devServerUrl, devUsername, devPassword)
    }
    LaunchedEffect(state.signedIn) {
        if (state.signedIn) onSignedIn()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("lugu", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Sign in to your Audiobookshelf server",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = viewModel::onServerUrlChange,
            label = { Text("Server address") },
            placeholder = { Text("audiobooks.example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { viewModel.checkServer() }),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Server address" },
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.username,
            onValueChange = viewModel::onUsernameChange,
            label = { Text("Username") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Username" },
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Password" },
        )

        if (state.isPlainHttp) {
            Spacer(Modifier.height(12.dp))
            // Stated where the address was typed, not in a dialog that has to be dismissed.
            // This is the ordinary way Audiobookshelf is run and the sign-in must not
            // obstruct it; what it must not do is let the password go out in the clear
            // without saying so.
            Text(
                "This address is plain HTTP. Your password, your token and everything you " +
                    "listen to travel unencrypted — fine on your own network, not over the " +
                    "internet. Use https:// if your server offers it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Plain HTTP warning" },
            )
        }

        state.error?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = viewModel::submit,
            enabled = state.canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Sign in")
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = viewModel::toggleAdvanced, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (state.advancedOpen) {
                    "Hide connection settings"
                } else {
                    "Behind a proxy? Connection settings"
                },
            )
        }

        if (state.advancedOpen) {
            LoginConnectionSettings(
                state = state,
                viewModel = viewModel,
                onPickCertificate = { pickCertificate.launch(LOGIN_CERTIFICATE_TYPES) },
            )
        }
    }

    state.draft?.let { draft -> LoginHeaderDialog(draft, state.draftProblem, viewModel) }
    if (state.pendingCertificate != null) LoginCertificateDialog(state, viewModel)
}

private val LOGIN_CERTIFICATE_TYPES =
    arrayOf("application/x-pkcs12", "application/pkcs12", "application/octet-stream")

/**
 * The subset of the connection screen that can be set before an account exists: the
 * headers and the certificate. The second address is not here, because it is stored
 * against the account and there is not one yet.
 */
@Composable
private fun LoginConnectionSettings(
    state: LoginUiState,
    viewModel: LoginViewModel,
    onPickCertificate: () -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    Text(
        "Headers are sent with every request to this address, including this sign-in. " +
            "They and the certificate password are stored encrypted on this device, " +
            "alongside your tokens, and go nowhere but this server.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    state.headers.forEach { header ->
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(header.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (header.name in state.revealed) header.value else header.maskedValue,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { viewModel.toggleReveal(header) }) {
                Icon(
                    if (header.name in state.revealed) {
                        Icons.Default.VisibilityOff
                    } else {
                        Icons.Default.Visibility
                    },
                    contentDescription = "Show or hide the value of ${header.name}",
                )
            }
            IconButton(onClick = { viewModel.startEditingHeader(header) }) {
                Icon(Icons.Default.Edit, contentDescription = "Edit ${header.name}")
            }
            IconButton(onClick = { viewModel.deleteHeader(header) }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete ${header.name}")
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = viewModel::startAddingHeader, modifier = Modifier.fillMaxWidth()) {
        Text("Add a header")
    }

    Spacer(Modifier.height(8.dp))
    val certificate = state.certificate
    if (certificate == null) {
        OutlinedButton(onClick = onPickCertificate, modifier = Modifier.fillMaxWidth()) {
            Text("Choose a client certificate")
        }
    } else {
        Text(
            "Client certificate: ${certificate.fileName}",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onPickCertificate) { Text("Replace") }
            TextButton(onClick = viewModel::removeCertificate) { Text("Remove") }
        }
    }
}

@Composable
private fun LoginHeaderDialog(draft: HeaderDraft, problem: String?, viewModel: LoginViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::cancelDraft,
        title = { Text(if (draft.original == null) "Add a header" else "Edit header") },
        text = {
            Column {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = viewModel::onDraftNameChange,
                    label = { Text("Name") },
                    placeholder = { Text("CF-Access-Client-Id") },
                    singleLine = true,
                    modifier = Modifier.semantics { contentDescription = "Header name" },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.value,
                    onValueChange = viewModel::onDraftValueChange,
                    label = { Text("Value") },
                    singleLine = true,
                    modifier = Modifier.semantics { contentDescription = "Header value" },
                )
                problem?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveDraft) { Text("Save") } },
        dismissButton = { TextButton(onClick = viewModel::cancelDraft) { Text("Cancel") } },
    )
}

@Composable
private fun LoginCertificateDialog(state: LoginUiState, viewModel: LoginViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::cancelCertificate,
        title = { Text("Certificate password") },
        text = {
            Column {
                OutlinedTextField(
                    value = state.certificatePassword,
                    onValueChange = viewModel::onCertificatePasswordChange,
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.semantics { contentDescription = "Certificate password" },
                )
                state.certificateProblem?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::confirmCertificate) { Text("Install") } },
        dismissButton = { TextButton(onClick = viewModel::cancelCertificate) { Text("Cancel") } },
    )
}
