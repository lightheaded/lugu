package io.github.lightheaded.lugu.notification

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.download.NewEpisodeIntent
import org.junit.Test

/**
 * The notifier in `:core:download` addresses this receiver by a class name it cannot check,
 * because it cannot see this module. This is the check: a rename or a move of the receiver
 * fails here instead of leaving two buttons that do nothing on the phone.
 */
class NewEpisodeActionReceiverTest {

    @Test
    fun `the notifier names this receiver`() {
        assertThat(NewEpisodeIntent.ACTION_RECEIVER).isEqualTo(NewEpisodeActionReceiver::class.java.name)
    }
}
