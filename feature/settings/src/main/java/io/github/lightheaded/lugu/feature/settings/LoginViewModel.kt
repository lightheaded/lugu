package io.github.lightheaded.lugu.feature.settings

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.SavedStateHandle
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
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.CredentialLossReport
import io.github.lightheaded.lugu.core.sync.SignInMemory
import io.github.lightheaded.lugu.core.sync.credentialLossMessage
import io.github.lightheaded.lugu.core.sync.SyncScheduler
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The sign-in screen's one argument: the account to sign in to again, when there is one.
 *
 * A server id reads `<address>#<user id>`, so it carries a colon, slashes and a hash. It
 * travels as URL-safe base64 for the reason `Routes.browseGroup` gives in the app module.
 */
object LoginArgs {
    const val ACCOUNT = "account"

    fun encode(serverId: String): String = Base64.encodeToString(
        serverId.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    fun decode(argument: String?): String? = argument?.takeIf { it.isNotBlank() }?.let {
        runCatching { String(Base64.decode(it, Base64.URL_SAFE), Charsets.UTF_8) }.getOrNull()
    }
}

/** Said on the sign-in screen when it opened because a sign-in ended on its own. */
const val SESSION_ENDED_NOTICE = "Your session ended — sign in again."

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
     * False until the prefill is known. The screen draws no fields before that, so the
     * address does not fill in, and a notice does not appear, a frame after the fields do.
     */
    val ready: Boolean = false,
    /**
     * The address and username came from the last sign-in rather than from typing, so the
     * address field offers to forget them.
     */
    val remembered: Boolean = false,
    /**
     * Why this screen appeared, when the reason is not an ordinary sign-out: a lost key,
     * or a sign-in that ended on its own while the account and its downloads stayed.
     *
     * Read once, when the screen is built, and never changed while it is on view. A
     * message that arrives later would move the fields under a thumb that already
     * reaches for them, and the law of this project is that a message can appear and
     * nothing else moves. The reason is already known before the screen draws: the
     * storage read fails at startup, long before anybody sees a field.
     */
    val lossNotice: String? = null,
    /**
     * The provider page to open in a browser, once the server has named one.
     *
     * Held as state rather than launched from the view model because launching a browser
     * needs an activity, and a view model that reached for one would keep it alive past a
     * rotation. The screen opens it and calls [onProviderPageOpened].
     */
    val providerPage: String? = null,
) {
    val canSubmit: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank() && !isBusy

    /** An address is all the provider route needs. There is no password to type. */
    val canUseProvider: Boolean get() = serverUrl.isNotBlank() && !isBusy

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
    private val signInMemory: SignInMemory,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(certificate = connectionPrefs.certificate()) }
        val requested = LoginArgs.decode(savedStateHandle.get<String>(LoginArgs.ACCOUNT))
        // Off the main thread: it reads the database and a preference file. The state flow
        // takes the update from any thread.
        viewModelScope.launch(Dispatchers.IO) { loadPrefill(requested) }
    }

    /**
     * Fills the address and the username before the fields are drawn.
     *
     * In order, the first that answers wins:
     * 1. The account named in the route. The Accounts screen's "Sign in again" names one.
     * 2. The active account, when its sign-in has ended. A rejected refresh leaves exactly
     *    that, and so does a launch after the refresh token expired with the app closed.
     * 3. The last sign-in that worked, which a sign-out does not forget.
     *
     * The first two keep the account row, its mirror and its downloads. Signing in again
     * updates the row in place, so nothing has to be downloaded again.
     */
    private suspend fun loadPrefill(requested: String?) {
        // Every read is guarded, and the screen is ready whatever they answer. The fields
        // stay hidden until it is, so a throw here would leave a sign-in screen with nothing
        // to sign in with — the one screen that must always work. Empty fields are the
        // answer to a read that fails.
        val lapsed: ActiveAccount? = runCatching {
            requested?.let { authRepository.storedAccount(it) } ?: authRepository.lapsedAccount()
        }.getOrNull()
        val last = if (lapsed == null) runCatching { signInMemory.last() }.getOrNull() else null
        val address = lapsed?.baseUrl ?: last?.baseUrl
        val headers = address?.let { runCatching { connectionPrefs.headers(it) }.getOrNull() }
        // A snapshot, not a subscription. See [LoginUiState.lossNotice].
        val lossNotice = credentialLossMessage(credentialLosses.lost.value)
            ?: SESSION_ENDED_NOTICE.takeIf { lapsed != null }
        _state.update {
            it.copy(
                serverUrl = address ?: it.serverUrl,
                username = lapsed?.username ?: last?.username ?: it.username,
                headers = headers ?: it.headers,
                remembered = lapsed == null && last != null,
                lossNotice = lossNotice,
                ready = true,
            )
        }
    }

    /**
     * Debug builds can prefill from a gitignored local.properties so signing in during
     * development is not a chore. Nothing here ever reaches a release build.
     *
     * Each field only when it is still empty. The remembered address and username come
     * first, and the password is never remembered, so this is what fills it.
     */
    fun prefill(serverUrl: String, username: String, password: String) {
        _state.update {
            it.copy(
                serverUrl = it.serverUrl.ifBlank { serverUrl },
                username = it.username.ifBlank { username },
                password = it.password.ifBlank { password },
            )
        }
    }

    /**
     * Forgets the remembered server and empties the fields it filled.
     *
     * The custom headers for that address stay. They are deleted on the connection screen,
     * where deleting them is the decision rather than a side effect.
     */
    fun forgetServer() {
        signInMemory.forget()
        _state.update {
            it.copy(serverUrl = "", username = "", headers = emptyList(), remembered = false, error = null)
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
        current.copy(serverUrl = value, error = null, headers = stored ?: current.headers, remembered = false)
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
                .onSuccess { account ->
                    signInMemory.remember(account.baseUrl, account.username)
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

    // region signing in through an identity provider

    /**
     * Asks the server for its provider page.
     *
     * Nothing is decided here about whether the server has OpenID switched on. It answers
     * a 400 with a plain-text reason when it does not, and that reason is what reaches the
     * error line — "OpenID is not enabled" and "Invalid redirect_uri" are both settings on
     * the server, and both are actionable only if the words survive.
     */
    fun useProvider() {
        val current = _state.value
        if (!current.canUseProvider) return
        persistHeaders()
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            authRepository.beginOidcSignIn(current.serverUrl)
                .onSuccess { attempt ->
                    _state.update { it.copy(isBusy = false, providerPage = attempt.authorizationUrl) }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            isBusy = false,
                            error = failure.message ?: "That server did not offer a sign-in provider",
                        )
                    }
                }
        }
    }

    /** The browser is open, so the page must not be opened again on the next recomposition. */
    fun onProviderPageOpened() = _state.update { it.copy(providerPage = null) }

    /**
     * Finishes a sign-in from the `lugu://oauth` redirect.
     *
     * Reached from the activity rather than from a control on this screen, because the
     * redirect arrives as an intent. Everything after this is the password route's own
     * path — see `AuthRepository.completeOidcSignIn`.
     */
    fun onProviderRedirect(uri: String) {
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            authRepository.completeOidcSignIn(uri)
                .onSuccess { account ->
                    signInMemory.remember(account.baseUrl, account.username)
                    SyncScheduler.syncNow(context)
                    credentialLosses.acknowledge()
                    _state.update { LoginUiState(signedIn = true) }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(isBusy = false, error = failure.message ?: "The sign-in did not finish")
                    }
                }
        }
    }

    /** Called when the browser closes with no redirect, so a stale attempt cannot be used. */
    fun abandonProviderSignIn() {
        authRepository.forgetOidcAttempt()
        _state.update { it.copy(isBusy = false, providerPage = null) }
    }

    // endregion

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
