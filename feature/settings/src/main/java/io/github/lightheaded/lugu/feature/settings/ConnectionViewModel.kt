package io.github.lightheaded.lugu.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.api.ConnectionCertificate
import io.github.lightheaded.lugu.core.api.ConnectionHeader
import io.github.lightheaded.lugu.core.api.ConnectionHeaders
import io.github.lightheaded.lugu.core.api.ConnectionProbe
import io.github.lightheaded.lugu.core.api.ConnectionRace
import io.github.lightheaded.lugu.core.api.ProbeOutcome
import io.github.lightheaded.lugu.core.api.ServerUrl
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.ConnectionPrefs
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A header being added or edited.
 *
 * [original] is the name it had before the edit, so renaming a header replaces it rather
 * than leaving the old one behind.
 */
data class HeaderDraft(
    val name: String = "",
    val value: String = "",
    val original: String? = null,
) {
    /** The value is a credential; a data class would print it into any log that took the state. */
    override fun toString(): String = "HeaderDraft($name)"
}

/**
 * Everything the connection screen shows.
 *
 * Header values are held here in the clear, because a text field cannot edit anything
 * else. What follows from that is that this type must never be printed: the two members
 * that carry a value override `toString`, so a state object that ends up in a log line or
 * an exception message says how many headers there are and not what they contain.
 */
data class ConnectionUiState(
    val serverAddress: String = "",
    val lanAddress: String = "",
    val savedLanAddress: String? = null,
    val headers: List<ConnectionHeader> = emptyList(),
    val revealed: Set<String> = emptySet(),
    val certificate: ConnectionCertificate? = null,
    val draft: HeaderDraft? = null,
    val draftProblem: String? = null,
    val pendingCertificate: Uri? = null,
    val certificatePassword: String = "",
    val certificateProblem: String? = null,
    val isTesting: Boolean = false,
    val testResult: String? = null,
    val error: String? = null,
) {
    val canSaveLanAddress: Boolean get() = lanAddress.trim() != savedLanAddress.orEmpty()

    val canTest: Boolean get() = lanAddress.isNotBlank() && !isTesting

    /** Counts and flags only. The certificate password and the header values are secrets. */
    override fun toString(): String =
        "ConnectionUiState(headers=${headers.size}, certificate=${certificate != null}, testing=$isTesting)"
}

/**
 * The connection settings, all of which exist because a server is behind something.
 *
 * The three are unrelated to each other and share a screen only because they share a
 * cause: a reverse proxy, an identity-aware proxy, or mutual TLS in front of the server.
 * Somebody who has none of them never opens this screen.
 */
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val connectionPrefs: ConnectionPrefs,
    private val probe: ConnectionProbe,
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectionUiState())
    val state: StateFlow<ConnectionUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val account = authRepository.account()
            _state.update {
                it.copy(
                    serverAddress = account?.baseUrl.orEmpty(),
                    lanAddress = account?.lanBaseUrl.orEmpty(),
                    savedLanAddress = account?.lanBaseUrl,
                    headers = account?.baseUrl?.let(connectionPrefs::headers).orEmpty(),
                    certificate = connectionPrefs.certificate(),
                )
            }
        }
    }

    // region second address

    fun onLanAddressChange(value: String) =
        _state.update { it.copy(lanAddress = value, error = null, testResult = null) }

    fun saveLanAddress() {
        val typed = _state.value.lanAddress.trim()
        viewModelScope.launch {
            authRepository.setLanBaseUrl(typed.takeIf { it.isNotEmpty() })
                .onSuccess { saved ->
                    _state.update {
                        it.copy(lanAddress = saved.orEmpty(), savedLanAddress = saved, error = null)
                    }
                }
                .onFailure { failure ->
                    _state.update { it.copy(error = failure.message ?: "That address could not be saved") }
                }
        }
    }

    /**
     * Says what happened rather than whether it worked. "Could not connect" is the answer
     * that sends people to re-read their router configuration when the real problem was
     * that the address is a different service entirely.
     */
    fun testLanAddress() {
        val address = _state.value.lanAddress.trim()
        if (address.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(isTesting = true, testResult = null) }
            // Normalised exactly as it will be when saved, so the test answers the question
            // that will actually be asked rather than a nearby one.
            val normalised = ServerUrl.normalise(address)
            if (normalised == null) {
                _state.update {
                    it.copy(isTesting = false, testResult = "That does not look like a server address.")
                }
                return@launch
            }
            val outcome = probe.probe(normalised, _state.value.headers)
            val message = when (outcome) {
                is ProbeOutcome.Answered -> {
                    val version = outcome.serverVersion?.let { " (version $it)" }.orEmpty()
                    "An Audiobookshelf server answered$version. lugu will use this address " +
                        "whenever it answers, and the other one when it does not."
                }

                ProbeOutcome.NotAudiobookshelf ->
                    "Something answered at that address, but it is not an Audiobookshelf server."

                is ProbeOutcome.Silent ->
                    "Nothing answered within ${ConnectionRace.DEFAULT_TIMEOUT_MS} milliseconds, " +
                        "which is the same deadline lugu uses. Reason given: ${outcome.reason}"
            }
            _state.update { it.copy(isTesting = false, testResult = message) }
        }
    }

    // endregion

    // region headers

    fun startAddingHeader() = _state.update { it.copy(draft = HeaderDraft(), draftProblem = null) }

    fun startEditingHeader(header: ConnectionHeader) = _state.update {
        it.copy(
            draft = HeaderDraft(header.name, header.value, original = header.name),
            draftProblem = null,
        )
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
        persist(kept + ConnectionHeader(name, draft.value))
        _state.update { it.copy(draft = null, draftProblem = null) }
    }

    fun deleteHeader(header: ConnectionHeader) {
        persist(_state.value.headers.filterNot { it.name == header.name })
        _state.update { it.copy(revealed = it.revealed - header.name) }
    }

    /** Revealing is per header and never sticky, so a screenshot of this screen shows nothing. */
    fun toggleReveal(header: ConnectionHeader) = _state.update {
        it.copy(
            revealed = if (header.name in it.revealed) {
                it.revealed - header.name
            } else {
                it.revealed + header.name
            },
        )
    }

    private fun persist(headers: List<ConnectionHeader>) {
        val address = _state.value.serverAddress
        if (address.isNotBlank()) connectionPrefs.setHeaders(address, headers)
        _state.update { it.copy(headers = headers) }
    }

    // endregion

    // region client certificate

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
                            certificateProblem = null,
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(certificateProblem = failure.message ?: "That certificate could not be used")
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

    // endregion
}
