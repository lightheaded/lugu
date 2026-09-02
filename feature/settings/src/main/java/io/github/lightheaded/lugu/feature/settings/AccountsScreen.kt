package io.github.lightheaded.lugu.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.download.DownloadRepository
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.StoredAccount
import io.github.lightheaded.lugu.core.ui.Status
import io.github.lightheaded.lugu.core.ui.StatusStrip
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountsUiState(
    val accounts: List<StoredAccount> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * The accounts on this device, and which one is in force.
 *
 * ## Why signing out removes the downloads first
 *
 * `:core:sync` cannot reach `:core:download`, so [AuthRepository.signOutOf] cannot take
 * the bytes with it, and its KDoc says the caller must. This is that caller. The order
 * matters: the download rows carry the server id, so removing them after the purge would
 * leave the repository nothing to find and the files unreachable — present on disk, still
 * counted against the storage cap, and belonging to an account the device no longer knows.
 */
@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val downloadRepository: DownloadRepository,
) : ViewModel() {

    private val transient = MutableStateFlow(TransientState())

    val state: StateFlow<AccountsUiState> =
        combine(authRepository.observeAccounts(), transient) { accounts, extra ->
            AccountsUiState(accounts = accounts, busy = extra.busy, error = extra.error)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    fun switchTo(serverId: String) {
        // A tap on the account already in force is not an error and not a switch.
        if (state.value.accounts.any { it.account.serverId == serverId && it.isActive }) return
        perform { authRepository.switchTo(serverId).map { } }
    }

    fun signOutOf(serverId: String) = perform {
        val account = state.value.accounts.firstOrNull { it.account.serverId == serverId }
        if (account != null) {
            downloadRepository.removeAllFor(account.account.serverId, account.account.userId)
        }
        authRepository.signOutOf(serverId)
    }

    fun dismissError() = transient.update { it.copy(error = null) }

    /**
     * Runs one account action, with the screen locked while it runs.
     *
     * Locked rather than merely showing a spinner: switching and signing out both rewrite
     * the active account, and two of them at once would race over the same row. The state
     * this reports is the only thing keeping a second tap out.
     */
    private fun perform(action: suspend () -> Result<Unit>) {
        if (transient.value.busy) return
        transient.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val outcome = action()
            transient.update {
                it.copy(
                    busy = false,
                    error = outcome.exceptionOrNull()?.message ?: it.error,
                )
            }
        }
    }

    private data class TransientState(val busy: Boolean = false, val error: String? = null)
}

@Composable
fun AccountsScreen(
    onBack: () -> Unit,
    onAddAccount: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AccountsContent(
        state = state,
        onBack = onBack,
        onAddAccount = onAddAccount,
        onSwitchTo = viewModel::switchTo,
        onSignOutOf = viewModel::signOutOf,
        onDismissError = viewModel::dismissError,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsContent(
    state: AccountsUiState,
    onBack: () -> Unit,
    onAddAccount: () -> Unit,
    onSwitchTo: (String) -> Unit,
    onSignOutOf: (String) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by remember { mutableStateOf<StoredAccount?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Accounts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        // The strip wraps only the scrolling content, and sits below no fixed control here
        // because this screen has none. See the placement contract on StatusStrip.
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.accounts, key = { it.account.serverId }) { account ->
                    AccountCard(
                        account = account,
                        enabled = !state.busy,
                        onSwitchTo = { onSwitchTo(account.account.serverId) },
                        onSignOut = { confirming = account },
                    )
                }

                item {
                    AssistChip(
                        onClick = onAddAccount,
                        enabled = !state.busy,
                        label = { Text("Add another account") },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                item {
                    Text(
                        text = "Each account keeps its own library, progress and downloads " +
                            "on this device. Nothing crosses between them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            StatusStrip(
                status = when {
                    state.error != null -> Status.Problem(state.error)
                    state.busy -> Status.Working("Working…")
                    else -> null
                },
                onDismiss = onDismissError,
            )
        }
    }

    confirming?.let { account ->
        SignOutDialog(
            account = account,
            onConfirm = {
                onSignOutOf(account.account.serverId)
                confirming = null
            },
            onDismiss = { confirming = null },
        )
    }
}

@Composable
private fun AccountCard(
    account: StoredAccount,
    enabled: Boolean,
    onSwitchTo: () -> Unit,
    onSignOut: () -> Unit,
) {
    Card(
        colors = if (account.isActive) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled && !account.isActive, onClick = onSwitchTo),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = account.account.username,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = account.account.baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (account.isActive) {
                    Icon(Icons.Default.Check, contentDescription = "The account in use")
                }
            }

            // Said only when it is false. A row that reads "signed in" on every account
            // teaches nobody anything, and the one that has lapsed is the whole point.
            if (!account.isSignedIn) {
                Text(
                    text = "Needs the password again",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!account.isActive) {
                    TextButton(onClick = onSwitchTo, enabled = enabled) { Text("Use this one") }
                }
                TextButton(onClick = onSignOut, enabled = enabled) { Text("Sign out") }
            }
        }
    }
}

/**
 * The confirmation, which names what goes.
 *
 * A sign-out here deletes that account's mirror and its downloaded audio, and neither is
 * recoverable without downloading it again. Listening position is not lost — it is on the
 * server — and saying so is what makes the rest of the warning readable rather than
 * frightening.
 */
@Composable
private fun SignOutDialog(
    account: StoredAccount,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign out of ${account.account.username}?") },
        text = {
            Text(
                "This removes the library, the downloads and the local record for this " +
                    "account on this device. Your listening position stays on the server, " +
                    "so signing back in restores it. Downloaded audio has to be " +
                    "downloaded again.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep it") } },
    )
}
