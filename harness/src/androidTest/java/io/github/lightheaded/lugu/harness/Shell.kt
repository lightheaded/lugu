package io.github.lightheaded.lugu.harness

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Commands run as the shell user, from inside the harness process.
 *
 * [android.app.UiAutomation.executeShellCommand] is the only way a test gets to do things
 * an app may not: stopping another package, killing its process, injecting a key event,
 * and reading `dumpsys`. It is the same shell `adb` gives, so a command that works here is
 * a command that can be pasted into the manual recipe in docs/qa/instrumented.md, and the
 * other way round.
 *
 * Output is read to the end before returning. Not doing so leaves the pipe full and the
 * command blocked, which shows up later as an unrelated command timing out.
 */
internal object Shell {

    fun run(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
