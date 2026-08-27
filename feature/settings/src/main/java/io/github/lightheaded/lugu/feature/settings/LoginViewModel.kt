package io.github.lightheaded.lugu.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lightheaded.lugu.core.api.ConnectionCertificate
import io.github.lightheaded.lugu.core.api.ConnectionHeader
import io.github.lightheaded.lugu.core.api.ConnectionHeaders
import io.github.lightheaded.lugu.core.api.ServerUrl
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.ConnectionPrefs
import io.github.lightheaded.lugu.core.sync.CredentialLossReport
import io.github.lightheaded.lugu.core.sync.credentialLossMessage
import io.github.lightheaded.lugu.core.sync.SyncScheduler
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isBusy: Boolean = false,
    val error: String? = null,
    val serverVersionHint: String? = null,
    val signedIn: Boolean = false,
    /**
     * The connection settings, shown folded away. Somebody behind an identity-aware proxy
     * cannot get as far as a password prompt without them, so they have to be reachable
     * from here — but they are noise for everybody else, which is why they are folded.
     */
    val advancedOpen: Boolean = false,
    val headers: List<ConnectionHeader> = emptyList(),
    val revealed: Set<String> = emptySet(),
    val draft: HeaderDraft? = null,
    val draftProblem: String? = null,
    val certificate: ConnectionCertificate? = null,
    val pendingCertificate: Uri? = null,
    val certificatePassword: String = "",
    val certificateProblem: String? = null,
    /**
     * Why this screen appeared, when the reason is not an ordinary sign-out.
     *
     * Read once, when the screen is built, and never changed while it is on view. A
     * message that arrives later would move the fields under a thumb that already
     * reaches for them, and the law of this project is that a message can appear and
     * nothing else moves. The reason is already known before the screen draws: the
     * storage read fails at startup, long before anybody sees a field.
     */
    val lossNotice: String? = null,
) {
    val canSubmit: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank() && !isBusy

    /**
     * Whether this address will be talked to in the clear.
     *
     * Said before the password is sent rather than after, and never as a refusal: a plain
     * HTTP server on a home network is the ordinary case for this software, and lugu
     * refusing it outright is what made those servers unreachable in the first place.
     */
    val isPlainHttp: Boolean get() = ServerUrl.isCleartext(serverUrl)

    /**
     * This state holds three separate secrets in the clear — the account password, the
     * certificate password and the header values — because text fields cannot hold
     * anything else. A data class would print all three into any log line, breadcrumb or
     * exception message that happened to take the state, so it does not get to.
     */
    override fun toString(): String =
        "LoginUiState(busy=$isBusy, signedIn=$signedIn, headers=${headers.size})"
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val connectionPrefs: ConnectionPrefs,
    private val credentialLosses: CredentialLossReport,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    init {
        _state.update {
            it.copy(
                certificate = connectionPrefs.certificate(),
                // A snapshot, not a subscription. See [LoginUiState.lossNotice].
                lossNotice = credentialLossMessage(credentialLosses.lost.value),
            )
        }
    }

    /**
     * Debug builds can prefill from a gitignored local.properties so signing in during
     * development is not a chore. Nothing here ever reaches a release build.
     */
    fun prefill(serverUrl: String, username: String, password: String) {
        _state.update {
            if (it.serverUrl.isNotBlank()) {
                it
            } else {
                it.copy(serverUrl = serverUrl, username = username, password = password)
            }
        }
    }

    /**
     * Changing the address also picks up any headers already stored for it, so somebody
     * who signed out and came back is not asked to type their proxy credentials again.
     * Only when nothing has been typed here yet: re-reading over a half-finished edit
     * would delete work in front of them.
     */
    fun onServerUrlChange(value: String) = _state.update { current ->
        val stored = ServerUrl.normalise(value)
            ?.takeIf { current.headers.isEmpty() }
            ?.let(connectionPrefs::headers)
        current.copy(serverUrl = value, error = null, headers = stored ?: current.headers)
    }

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    /** Separating "is this a server" from "are these the right credentials" makes errors honest. */
    fun checkServer() {
        val url = _state.value.serverUrl
        if (url.isBlank()) return
        persistHeaders()
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true) }
            authRepository.probe(url)
                .onSuccess { normalised ->
                    _state.update { it.copy(isBusy = false, serverUrl = normalised, error = null) }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(isBusy = false, error = failure.message ?: "Could not reach that server")
                    }
                }
        }
    }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        // Written before the request rather than after it succeeds: the probe and the login
        // are themselves the requests that need the headers.
        persistHeaders()
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            authRepository.login(current.serverUrl, current.username, current.password)
                .onSuccess {
                    // Signing in is the moment the mirror should fill, and nothing else was
                    // going to do it: the only on-demand sync belongs to the Library tab,
                    // and this screen hands over to Home.
                    SyncScheduler.syncNow(context)
                    // The reason for asking is history once the listener has answered it.
                    credentialLosses.acknowledge()
                    // Drop the password from memory the moment it is no longer needed.
                    _state.update { LoginUiState(signedIn = true) }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(isBusy = false, error = failure.message ?: "Could not sign in")
                    }
                }
        }
    }

    // region connection settings, before there is an account to attach them to

    fun toggleAdvanced() = _state.update { it.copy(advancedOpen = !it.advancedOpen) }

    fun startAddingHeader() = _state.update { it.copy(draft = HeaderDraft(), draftProblem = null) }

    fun startEditingHeader(header: ConnectionHeader) = _state.update {
        it.copy(draft = HeaderDraft(header.name, header.value, header.name), draftProblem = null)
    }

    fun onDraftNameChange(value: String) =
        _state.update { it.copy(draft = it.draft?.copy(name = value), draftProblem = null) }

    fun onDraftValueChange(value: String) =
        _state.update { it.copy(draft = it.draft?.copy(value = value), draftProblem = null) }

    fun cancelDraft() = _state.update { it.copy(draft = null, draftProblem = null) }

    fun saveDraft() {
        val draft = _state.value.draft ?: return
        val name = draft.name.trim()
        ConnectionHeaders.problemWith(name, draft.value)?.let { problem ->
            _state.update { it.copy(draftProblem = problem) }
            return
        }
        val kept = _state.value.headers.filterNot {
            it.name.equals(name, ignoreCase = true) || it.name == draft.original
        }
        _state.update { it.copy(headers = kept + ConnectionHeader(name, draft.value), draft = null) }
        persistHeaders()
    }

    fun deleteHeader(header: ConnectionHeader) {
        _state.update {
            it.copy(
                headers = it.headers.filterNot { existing -> existing.name == header.name },
                revealed = it.revealed - header.name,
            )
        }
        persistHeaders()
    }

    fun toggleReveal(header: ConnectionHeader) = _state.update {
        it.copy(
            revealed = if (header.name in it.revealed) {
                it.revealed - header.name
            } else {
                it.revealed + header.name
            },
        )
    }

    fun onCertificatePicked(uri: Uri?) = _state.update {
        it.copy(pendingCertificate = uri, certificatePassword = "", certificateProblem = null)
    }

    fun onCertificatePasswordChange(value: String) =
        _state.update { it.copy(certificatePassword = value, certificateProblem = null) }

    fun confirmCertificate() {
        val uri = _state.value.pendingCertificate ?: return
        val password = _state.value.certificatePassword
        viewModelScope.launch {
            connectionPrefs.installCertificate(uri, password)
                .onSuccess { certificate ->
                    _state.update {
                        it.copy(
                            certificate = certificate,
                            pendingCertificate = null,
                            certificatePassword = "",
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            certificateProblem = failure.message ?: "That certificate could not be used",
                        )
                    }
                }
        }
    }

    fun cancelCertificate() = _state.update {
        it.copy(pendingCertificate = null, certificatePassword = "", certificateProblem = null)
    }

    fun removeCertificate() {
        connectionPrefs.removeCertificate()
        _state.update { it.copy(certificate = null) }
    }

    /**
     * Headers are keyed by the address they were entered for, and the address is still
     * being typed — so they are written under whatever it normalises to at the moment they
     * are needed. A half-typed address stores nothing rather than storing a secret under a
     * key nothing will ever look up.
     */
    private fun persistHeaders() {
        val address = ServerUrl.normalise(_state.value.serverUrl) ?: return
        connectionPrefs.setHeaders(address, _state.value.headers)
    }

    // endregion
}
