package io.github.lightheaded.lugu.playback

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.sync.NotificationPersistence
import org.junit.Test

/**
 * The three moments the persistence setting is felt: a pause, a swipe out of Recents, and
 * opening the app.
 *
 * Worth testing rather than reading, because two of the three are only observable minutes
 * after the thing that caused them — a notification that has gone by the time anybody looks
 * cannot be told from one that was never posted.
 */
class PersistencePolicyTest {

    // The pause.

    @Test
    fun `the quiet setting never holds the service open`() {
        assertThat(
            PersistencePolicy.holdsForegroundWhilePaused(
                persistence = NotificationPersistence.WHILE_PLAYING,
                phase = PlaybackPhase.LOADED,
                hasPlayed = true,
                foregroundNotificationIsDismissible = true,
            ),
        ).isFalse()
    }

    @Test
    fun `both persistent settings hold a paused book`() {
        for (persistence in listOf(
            NotificationPersistence.UNTIL_DISMISSED,
            NotificationPersistence.ALWAYS_READY,
        )) {
            assertThat(
                PersistencePolicy.holdsForegroundWhilePaused(
                    persistence = persistence,
                    phase = PlaybackPhase.LOADED,
                    hasPlayed = true,
                    foregroundNotificationIsDismissible = true,
                ),
            ).isTrue()
        }
    }

    /**
     * Before Android 14 a foreground notification cannot be swiped away, so holding the
     * service would trade a notification that vanishes for one that cannot be got rid of.
     */
    @Test
    fun `nothing is held where a held notification could not be dismissed`() {
        assertThat(
            PersistencePolicy.holdsForegroundWhilePaused(
                persistence = NotificationPersistence.UNTIL_DISMISSED,
                phase = PlaybackPhase.LOADED,
                hasPlayed = true,
                foregroundNotificationIsDismissible = false,
            ),
        ).isFalse()
    }

    /** A dismissal stops the player, and a stopped player has no notification to hold. */
    @Test
    fun `a stopped or finished player is let go`() {
        for (phase in listOf(PlaybackPhase.IDLE, PlaybackPhase.ENDED, PlaybackPhase.EMPTY)) {
            assertThat(
                PersistencePolicy.holdsForegroundWhilePaused(
                    persistence = NotificationPersistence.UNTIL_DISMISSED,
                    phase = phase,
                    hasPlayed = true,
                    foregroundNotificationIsDismissible = true,
                ),
            ).isFalse()
        }
    }

    @Test
    fun `an item that was only armed does not earn a foreground service`() {
        assertThat(
            PersistencePolicy.holdsForegroundWhilePaused(
                persistence = NotificationPersistence.ALWAYS_READY,
                phase = PlaybackPhase.LOADED,
                hasPlayed = false,
                foregroundNotificationIsDismissible = true,
            ),
        ).isFalse()
    }

    // The swipe out of Recents.

    @Test
    fun `the quiet setting still ends a paused session with the task`() {
        assertThat(
            PersistencePolicy.stopsWhenTaskRemoved(
                persistence = NotificationPersistence.WHILE_PLAYING,
                phase = PlaybackPhase.LOADED,
                wantsToPlay = false,
            ),
        ).isTrue()
    }

    @Test
    fun `a persistent setting survives the app being swiped away`() {
        for (persistence in listOf(
            NotificationPersistence.UNTIL_DISMISSED,
            NotificationPersistence.ALWAYS_READY,
        )) {
            assertThat(
                PersistencePolicy.stopsWhenTaskRemoved(
                    persistence = persistence,
                    phase = PlaybackPhase.LOADED,
                    wantsToPlay = false,
                ),
            ).isFalse()
        }
    }

    /** Stopping audio because a task was swiped would be a bug on any setting. */
    @Test
    fun `something playing is never stopped by the swipe`() {
        for (persistence in NotificationPersistence.entries) {
            assertThat(
                PersistencePolicy.stopsWhenTaskRemoved(
                    persistence = persistence,
                    phase = PlaybackPhase.LOADED,
                    wantsToPlay = true,
                ),
            ).isFalse()
        }
    }

    @Test
    fun `an empty player is always stopped, there being nothing to be persistent about`() {
        for (persistence in NotificationPersistence.entries) {
            assertThat(
                PersistencePolicy.stopsWhenTaskRemoved(
                    persistence = persistence,
                    phase = PlaybackPhase.EMPTY,
                    wantsToPlay = false,
                ),
            ).isTrue()
        }
    }

    // Opening the app.

    @Test
    fun `only the ready setting arms anything`() {
        for (persistence in listOf(
            NotificationPersistence.WHILE_PLAYING,
            NotificationPersistence.UNTIL_DISMISSED,
        )) {
            assertThat(
                PersistencePolicy.armsOnOpen(
                    persistence = persistence,
                    phase = PlaybackPhase.EMPTY,
                    wantsToPlay = false,
                    alreadyArming = false,
                ),
            ).isFalse()
        }
        assertThat(
            PersistencePolicy.armsOnOpen(
                persistence = NotificationPersistence.ALWAYS_READY,
                phase = PlaybackPhase.EMPTY,
                wantsToPlay = false,
                alreadyArming = false,
            ),
        ).isTrue()
    }

    /** Replacing a book somebody has just started is the worst thing arming could do. */
    @Test
    fun `arming gives way to a real play request`() {
        assertThat(
            PersistencePolicy.armsOnOpen(
                persistence = NotificationPersistence.ALWAYS_READY,
                phase = PlaybackPhase.EMPTY,
                wantsToPlay = true,
                alreadyArming = false,
            ),
        ).isFalse()
    }

    @Test
    fun `nothing is armed while something is already loaded`() {
        assertThat(
            PersistencePolicy.armsOnOpen(
                persistence = NotificationPersistence.ALWAYS_READY,
                phase = PlaybackPhase.LOADED,
                wantsToPlay = false,
                alreadyArming = false,
            ),
        ).isFalse()
    }

    /** A dismissal leaves the item in the player and stops it; that is the cheap case. */
    @Test
    fun `a stopped player is armed again`() {
        assertThat(
            PersistencePolicy.armsOnOpen(
                persistence = NotificationPersistence.ALWAYS_READY,
                phase = PlaybackPhase.IDLE,
                wantsToPlay = false,
                alreadyArming = false,
            ),
        ).isTrue()
    }

    @Test
    fun `a second request while one is in flight is dropped`() {
        assertThat(
            PersistencePolicy.armsOnOpen(
                persistence = NotificationPersistence.ALWAYS_READY,
                phase = PlaybackPhase.EMPTY,
                wantsToPlay = false,
                alreadyArming = true,
            ),
        ).isFalse()
    }
}
