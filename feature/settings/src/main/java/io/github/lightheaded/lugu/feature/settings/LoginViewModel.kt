package io.github.lightheaded.lugu.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.sync.AuthRepository
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
) {
    val canSubmit: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank() && !isBusy
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

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

    fun onServerUrlChange(value: String) = _state.update { it.copy(serverUrl = value, error = null) }

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    /** Separating "is this a server" from "are these the right credentials" makes errors honest. */
    fun checkServer() {
        val url = _state.value.serverUrl
        if (url.isBlank()) return
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
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            authRepository.login(current.serverUrl, current.username, current.password)
                .onSuccess {
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
}
