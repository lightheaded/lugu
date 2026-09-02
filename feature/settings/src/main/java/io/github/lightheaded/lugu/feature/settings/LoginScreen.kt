package io.github.lightheaded.lugu.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lightheaded.lugu.core.ui.ReservedMessage
import io.github.lightheaded.lugu.core.ui.reservedSpace

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
    /** What came back on `lugu://oauth`, or null. See [LoginViewModel.onProviderRedirect]. */
    providerRedirect: String? = null,
    onProviderRedirectHandled: () -> Unit = {},
    devServerUrl: String = "",
    devUsername: String = "",
    devPassword: String = "",
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The browser is opened here and not in the view model, which has no activity and must
    // not hold one. A Custom Tab rather than a WebView: RFC 8252 says a native app must not
    // put itself between somebody and their provider's password field, and a Custom Tab also
    // shares whatever session the browser already holds, so most sign-ins need no password
    // typed at all.
    LaunchedEffect(providerRedirect) {
        val redirect = providerRedirect ?: return@LaunchedEffect
        // Cleared before the exchange rather than after it. The exchange can fail, and a
        // redirect left in place would be replayed on the next recomposition with a code
        // the server has already spent.
        onProviderRedirectHandled()
        viewModel.onProviderRedirect(redirect)
    }

    val context = LocalContext.current
    LaunchedEffect(state.providerPage) {
        val page = state.providerPage ?: return@LaunchedEffect
        viewModel.onProviderPageOpened()
        val opened = runCatching {
            CustomTabsIntent.Builder().setShowTitle(true).build()
                .launchUrl(context, Uri.parse(page))
        }
        // A phone with no browser at all throws rather than declining. The sign-in cannot
        // continue, and the attempt has to go with it so no later redirect matches it.
        if (opened.isFailure) viewModel.abandonProviderSignIn()
    }

    // Deliberately not rememberSaveable: a revealed password must not come back revealed
    // after the process is recreated with the screen still on somebody's desk.
    var passwordShown by remember { mutableStateOf(false) }

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
        // Why this screen appeared, when a lost key is the reason and not a sign-out. It
        // sits above the first field, because the question it answers — "why is it asking
        // me again?" — comes before the answer a person types.
        //
        // Added and removed rather than held in reserved space, and that is allowed here
        // where it is not allowed elsewhere. The value is read once when the screen is
        // built and cannot change while the screen is on view, so nothing moves under a
        // thumb that already reaches for a field. Reserved space would instead keep an
        // empty block above every ordinary sign-in, for a message almost nobody meets.
        state.lossNotice?.let { notice ->
            Spacer(Modifier.height(24.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = viewModel::onServerUrlChange,
            label = { Text("Server address") },
            placeholder = { Text("audiobooks.example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { viewModel.checkServer() }),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Server address" },
        )

        Spacer(Modifier.height(8.dp))
        // Stated where the address was typed, not in a dialog that has to be dismissed.
        // This is the ordinary way Audiobookshelf is run and the sign-in must not
        // obstruct it; what it must not do is let the password go out in the clear
        // without saying so. See docs/FEEDBACK.md — the wording is the decision.
        //
        // It is a standing condition of the address rather than a message about an
        // action, so its space is reserved whether or not it is true: composed always,
        // hidden with alpha when the address is https, and the same height in both
        // states. Added and removed, it moved the username field, the password field and
        // the Sign in button every time somebody typed or deleted an "s".
        Text(
            "This address is plain HTTP. Your password, your token and everything you " +
                "listen to travel unencrypted — fine on your own network, not over the " +
                "internet. Use https:// if your server offers it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .reservedSpace(state.isPlainHttp),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.username,
            onValueChange = viewModel::onUsernameChange,
            label = { Text("Username") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Username"
                    contentType = ContentType.Username
                },
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = if (passwordShown) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { passwordShown = !passwordShown }) {
                    Icon(
                        if (passwordShown) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (passwordShown) "Hide password" else "Show password",
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Password"
                    contentType = ContentType.Password
                },
        )

        // Under the password box, because that is where a reader looks after a sign-in is
        // refused, and in space that is reserved whether or not there is anything to say.
        // The message that says the password was wrong must not be the thing that moves
        // the Sign in button out from under the thumb that pressed it.
        Spacer(Modifier.height(4.dp))
        ReservedMessage(state.error, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(16.dp))
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

        // Offered to everybody rather than only where a server has OpenID switched on.
        // Nothing lugu can read before a sign-in says whether it does: /status does not
        // report it, and asking would mean a request per address typed. So the button is
        // always here, and a server without a provider answers in words that reach the
        // error line above.
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = viewModel::useProvider,
            enabled = state.canUseProvider,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sign in with your provider")
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
                // A dialog is a column too, and a problem added to the bottom of it moved
                // Save and Cancel down under the finger that had just reached for them.
                Spacer(Modifier.height(4.dp))
                ReservedMessage(problem)
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
                Spacer(Modifier.height(4.dp))
                ReservedMessage(state.certificateProblem)
            }
        },
        confirmButton = { TextButton(onClick = viewModel::confirmCertificate) { Text("Install") } },
        dismissButton = { TextButton(onClick = viewModel::cancelCertificate) { Text("Cancel") } },
    )
}
