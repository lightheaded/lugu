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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.KeyboardOptions
import io.github.lightheaded.lugu.core.api.ConnectionCertificate
import io.github.lightheaded.lugu.core.api.ConnectionHeader
import java.text.DateFormat
import java.util.Date

/**
 * The three settings that exist because something sits in front of the server.
 *
 * They are one screen rather than three rows in the settings list because they fail
 * together and are diagnosed together: somebody who cannot sign in behind Cloudflare
 * Access, somebody whose server is slow through a reverse proxy at home, and somebody
 * whose handshake is refused for want of a certificate are all looking for the same
 * place. Everybody else never opens it.
 *
 * The storage note at the bottom is not boilerplate. These are the only settings in lugu
 * that are credentials for something other than Audiobookshelf itself, and a person is
 * entitled to know where a Cloudflare Access client secret went before they type it in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConnectionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The system picker, so lugu never asks for storage permission and never sees a file
    // the person did not choose. PKCS#12 has two mime types in the wild and plenty of
    // exporters set neither, so the filter is deliberately loose.
    val pickCertificate = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> viewModel.onCertificatePicked(uri) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Connection") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            LocalAddressSection(state, viewModel)
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            HeadersSection(state, viewModel)
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            CertificateSection(state, viewModel, onPick = { pickCertificate.launch(CERTIFICATE_TYPES) })
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            StorageNote()
            Spacer(Modifier.height(32.dp))
        }
    }

    state.draft?.let { draft -> HeaderDialog(draft, state.draftProblem, viewModel) }
    if (state.pendingCertificate != null) CertificatePasswordDialog(state, viewModel)
}

/** The two mime types PKCS#12 is exported as, plus the catch-all for exporters that set neither. */
private val CERTIFICATE_TYPES =
    arrayOf("application/x-pkcs12", "application/pkcs12", "application/octet-stream")

@Composable
private fun LocalAddressSection(state: ConnectionUiState, viewModel: ConnectionViewModel) {
    Spacer(Modifier.height(8.dp))
    Text("A second address", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "A reverse proxy is materially slower than a direct connection. Give lugu the " +
            "server's address on your own network and it will use that one whenever it " +
            "answers, and the other one when it does not.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "Which address is used is decided by trying, never by looking at which network you " +
            "are on: reading the name of a Wi-Fi network needs the location permission, and " +
            "your location is not a fair price for a book loading faster.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (state.serverAddress.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Text("You sign in at ${state.serverAddress}", style = MaterialTheme.typography.bodySmall)
    }

    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = state.lanAddress,
        onValueChange = viewModel::onLanAddressChange,
        label = { Text("Address on your network") },
        placeholder = { Text("192.168.1.10:13378") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Address on your network" },
    )

    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = viewModel::saveLanAddress, enabled = state.canSaveLanAddress) {
            Text("Save")
        }
        OutlinedButton(onClick = viewModel::testLanAddress, enabled = state.canTest) {
            if (state.isTesting) {
                CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Test this now")
            }
        }
    }

    state.testResult?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
    state.error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun HeadersSection(state: ConnectionUiState, viewModel: ConnectionViewModel) {
    Text("Custom headers", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Sent with every request lugu makes to this server, including the sign-in, the " +
            "cover images and the audio itself. This is what an identity-aware proxy such " +
            "as Cloudflare Access needs before it will let anything through.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (state.headers.isEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            "No headers are being sent.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    state.headers.forEach { header ->
        HeaderRow(
            header = header,
            revealed = header.name in state.revealed,
            onReveal = { viewModel.toggleReveal(header) },
            onEdit = { viewModel.startEditingHeader(header) },
            onDelete = { viewModel.deleteHeader(header) },
        )
    }

    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = viewModel::startAddingHeader) { Text("Add a header") }
}

@Composable
private fun HeaderRow(
    header: ConnectionHeader,
    revealed: Boolean,
    onReveal: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(header.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                // Masked by default: this screen is read over shoulders and screenshotted
                // into bug reports, and the value is as good as a password.
                if (revealed) header.value else header.maskedValue,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onReveal) {
            Icon(
                if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = if (revealed) {
                    "Hide the value of ${header.name}"
                } else {
                    "Show the value of ${header.name}"
                },
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = "Edit ${header.name}")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete ${header.name}")
        }
    }
}

@Composable
private fun HeaderDialog(draft: HeaderDraft, problem: String?, viewModel: ConnectionViewModel) {
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
private fun CertificateSection(
    state: ConnectionUiState,
    viewModel: ConnectionViewModel,
    onPick: () -> Unit,
) {
    Text("Client certificate", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "If your server asks clients to prove who they are with a certificate, the " +
            "connection fails before there is anything to explain. Choose the PKCS#12 " +
            "file — a .p12 or .pfx — and give its password.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    val certificate = state.certificate
    if (certificate == null) {
        Text(
            "No certificate is installed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onPick) { Text("Choose a certificate") }
    } else {
        InstalledCertificate(certificate)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPick) { Text("Replace") }
            TextButton(onClick = viewModel::removeCertificate) { Text("Remove") }
        }
    }
}

@Composable
private fun InstalledCertificate(certificate: ConnectionCertificate) {
    Text(certificate.fileName, style = MaterialTheme.typography.bodyLarge)
    Text(
        certificate.subject,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "Issued by ${certificate.issuer}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        // Stated rather than left to be discovered: an expired client certificate fails as
        // a refused handshake, which looks like the server going away.
        "Valid until ${DateFormat.getDateInstance().format(Date(certificate.expiresAtMs))}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CertificatePasswordDialog(state: ConnectionUiState, viewModel: ConnectionViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::cancelCertificate,
        title = { Text("Certificate password") },
        text = {
            Column {
                Text(
                    "The password that opens the file you chose.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
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

/**
 * Where these went, in the same words whether or not anybody reads them.
 *
 * A header value and a certificate password are credentials for somebody else's system,
 * and the honest thing to say about them is both where they are kept and where they go.
 */
@Composable
private fun StorageNote() {
    Text("Where these are kept", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Header values, the certificate and its password are stored encrypted on this " +
            "device, in the same place as your sign-in tokens. They are never written to " +
            "the app's database, never written to a log, never attached to feedback and " +
            "never included in a crash report. They leave the device only as headers on " +
            "requests to the server you entered them for, and to no other address.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
