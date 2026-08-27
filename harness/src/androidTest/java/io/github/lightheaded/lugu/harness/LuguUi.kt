package io.github.lightheaded.lugu.harness

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/**
 * lugu's screen, as the only thing the harness may touch it with: what a person can see and
 * press.
 *
 * Two tests now walk the same three steps — open the app, sign in if it asks, open the
 * Library tab — so the steps live in one place. The words are the words on the screen,
 * because that is all another app has. A rename in lugu fails these tests, which is correct:
 * a label that moves takes every automation with it.
 *
 * Each function reports what it saw and asserts nothing. The message that belongs with a
 * failure is different in each test, and a helper that guessed it would report the wrong
 * reason for the right failure.
 */
internal object LuguUi {

    const val LIBRARY_TAB = "Library"
    const val SIGN_IN = "Sign in"

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** True once lugu owns the screen. */
    fun awaitWindow(): Boolean =
        device.wait(Until.hasObject(By.pkg(Lugu.PACKAGE).depth(0)), WINDOW_TIMEOUT_MS)

    /**
     * Taps the sign-in button if lugu is asking, and does nothing if it is not.
     *
     * The fields are already filled in on a debug build, from the same local.properties the
     * app module reads — which is the whole reason the harness needs no credentials of its
     * own. It taps a button; it never learns what is in the fields.
     */
    fun signInIfAsked() {
        awaitWindow()
        device.wait(Until.findObject(By.text(SIGN_IN)), SIGN_IN_TIMEOUT_MS)?.click()
        device.wait(Until.gone(By.text(SIGN_IN)), SIGN_IN_TIMEOUT_MS)
    }

    /** Opens the Library tab, which is what a person does to see the whole library. */
    fun openLibraryTab() {
        device.wait(Until.findObject(By.text(LIBRARY_TAB)), TAB_TIMEOUT_MS)?.click()
    }

    /**
     * Whether the screen holds [text] now.
     *
     * A title reaches the tree as a label on a tile or as its description, and which one it
     * is depends on the shelf it is in. Both count as "a person can see it".
     */
    fun shows(text: String): Boolean =
        device.hasObject(By.text(text)) || device.hasObject(By.desc(text))

    /** Whether lugu is asking for credentials now. */
    fun asksToSignIn(): Boolean = device.hasObject(By.text(SIGN_IN))

    private const val WINDOW_TIMEOUT_MS = 30_000L
    private const val SIGN_IN_TIMEOUT_MS = 15_000L
    private const val TAB_TIMEOUT_MS = 15_000L
}
