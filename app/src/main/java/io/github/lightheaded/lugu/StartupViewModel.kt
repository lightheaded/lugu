package io.github.lightheaded.lugu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where the app opens.
 *
 * [HasAccount] opens the library, and it includes an account whose sign-in has ended: its
 * downloads still play and its mirror still reads, so the sign-in is offered on top of the
 * library rather than in place of it. Only [NoAccount] opens on the sign-in screen.
 */
enum class StartupState { Checking, HasAccount, NoAccount }

/**
 * The start decision, apart from the view model so that a test can ask it directly.
 *
 * Guarded, and the guard is the second one on this path rather than the first.
 * `SecurePrefs` already answers a broken store with nothing instead of a throw. This is
 * still the last place a failure can reach: a throw here ends the app on the splash screen,
 * on every launch, and no launch can clear it. The sign-in screen is the correct answer to
 * every question this call cannot answer, so it is the answer to a failure as well.
 */
internal suspend fun decideStart(hasActiveAccount: suspend () -> Boolean): StartupState =
    if (runCatching { hasActiveAccount() }.getOrDefault(false)) StartupState.HasAccount else StartupState.NoAccount

/**
 * Decides the start destination once, from the stored account, and then watches for a
 * sign-in that ends while the app runs.
 *
 * Deliberately does not wait on the network: a stored session is treated as valid
 * until a request says otherwise, so opening the app offline lands on the library
 * rather than on a login form. Nor does it wait on the tokens: a session that has ended
 * still opens the library, for the reason [StartupState] gives.
 */
@HiltViewModel
class StartupViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(StartupState.Checking)
    val state: StateFlow<StartupState> = _state.asStateFlow()

    /**
     * The active account once its sign-in has ended, or null.
     *
     * The start destination is decided once, so it cannot answer a refresh token that the
     * server rejects an hour later. This can: the shell opens the sign-in screen when it
     * turns non-null. A failure reads as "nothing ended", for the reason [decideStart]
     * gives — this flow is the last place a throw can reach.
     */
    val lapsedAccount: StateFlow<ActiveAccount?> = authRepository.observeLapsedAccount()
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            // Off the main thread: the splash waits on this answer, and the database is
            // not to be read on the main thread.
            _state.value = withContext(Dispatchers.IO) { decideStart { authRepository.hasActiveAccount() } }
        }
    }
}
