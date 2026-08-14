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
            _state.value = if (authRepository.isSignedIn()) StartupState.SignedIn else StartupState.SignedOut
        }
    }
}
