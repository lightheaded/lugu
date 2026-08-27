package io.github.lightheaded.lugu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.sync.AuthRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class StartupState { Checking, SignedIn, SignedOut }

/**
 * Decides the start destination once, from stored credentials.
 *
 * Deliberately does not wait on the network: a stored session is treated as valid
 * until a request says otherwise, so opening the app offline lands on the library
 * rather than on a login form.
 */
@HiltViewModel
class StartupViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(StartupState.Checking)
    val state: StateFlow<StartupState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Guarded, and the guard is the second one on this path rather than the first.
            // `SecurePrefs` already answers a broken store with nothing instead of a throw.
            // This coroutine is still the last place a failure can reach: a throw here ends
            // the app on the splash screen, on every launch, and no launch can clear it.
            // The sign-in screen is the correct answer to every question this call cannot
            // answer, so it is the answer to a failure as well.
            val signedIn = runCatching { authRepository.isSignedIn() }.getOrDefault(false)
            _state.value = if (signedIn) StartupState.SignedIn else StartupState.SignedOut
        }
    }
}
