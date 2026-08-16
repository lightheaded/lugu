package io.github.lightheaded.lugu.playback

import io.github.lightheaded.lugu.core.sync.NotificationPersistence

/**
 * Where the player has got to, in the only terms the persistence rules care about.
 *
 * Deliberately coarser than `Player.STATE_*`: buffering and ready are the same thing to
 * every rule here, and the states that are *not* the same — nothing loaded, stopped, and
 * finished — are the three that decide whether a notification has any business existing.
 */
enum class PlaybackPhase {
    /** Nothing loaded at all, so there is nothing for a notification to be about. */
    EMPTY,

    /** Something loaded, playing or one press away from it. */
    LOADED,

    /** Stopped. Reached by a dismissal, by a failure, or by never having been prepared. */
    IDLE,

    /** Ran to the end of what was loaded. */
    ENDED,
}

/**
 * How persistent lugu is allowed to be, as pure decisions.
 *
 * All three questions this answers — does the notification outlive a pause, does swiping the
 * app away end the session, does opening the app load the last thing played — are the same
 * question asked at three moments, and the listener answers it once through
 * [NotificationPersistence]. Keeping the rules here rather than in the service means the
 * answers can be held to in a test, which matters because two of the three are only
 * observable minutes after the event that caused them.
 *
 * The bias throughout is that lugu may keep a place ready and may not take anything over.
 * Arming loads; it never starts. That distinction is the whole reason the persistence
 * setting is separate from the resume-on-headphones switch, which is off by default.
 */
object PersistencePolicy {

    /**
     * Asks the service to load the last thing played.
     *
     * A session command rather than a method on the service because the trigger comes from
     * the app process coming to the foreground, which reaches the service only through the
     * controller it already holds.
     */
    const val COMMAND_ARM_LAST_PLAYED = "io.github.lightheaded.lugu.ARM_LAST_PLAYED"

    /**
     * Whether the service should hold itself in the foreground past the point Media3 would
     * let it go.
     *
     * Media3 already keeps a paused notification: it posts it detached and leaves the
     * foreground, so the notification survives while the process does. What it cannot
     * promise is the process, and a media notification whose session has died is removed by
     * the system — which is exactly the "it disappeared after a couple of minutes" this
     * setting exists to answer. Media3's own foreground grace is capped at ten minutes
     * (`DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS`), so past that the only thing that keeps a
     * paused notification alive is a foreground service.
     *
     * [foregroundNotificationIsDismissible] is why this is not simply "yes, when paused".
     * Before Android 14 a foreground service's notification cannot be swiped away at all,
     * which is precisely why Media3 detaches on pause in the first place. Pinning the
     * service on those versions would trade a notification that vanishes for one that cannot
     * be got rid of, and the second complaint is the worse of the two. So on Android 13 and
     * earlier the answer is no, and persistence there rests on the service simply not being
     * stopped — see [stopsWhenTaskRemoved].
     *
     * @param hasPlayed whether anything has actually played since the service was created.
     *   An item that was merely armed has not earned a foreground service: nobody has asked
     *   for it yet, and it will be armed again the next time the app is opened.
     */
    fun holdsForegroundWhilePaused(
        persistence: NotificationPersistence,
        phase: PlaybackPhase,
        hasPlayed: Boolean,
        foregroundNotificationIsDismissible: Boolean,
    ): Boolean {
        if (!persistence.keepsWhilePaused) return false
        if (!foregroundNotificationIsDismissible) return false
        // Nothing loaded, stopped, or finished: all three are the notification's own end,
        // and holding the service open through them is holding it open for nothing.
        if (phase != PlaybackPhase.LOADED) return false
        return hasPlayed
    }

    /**
     * Whether swiping the app out of Recents should stop the service.
     *
     * Under [NotificationPersistence.WHILE_PLAYING] this is unchanged: a paused session ends
     * with the task, which is the quiet behaviour that setting promises.
     *
     * Under the other two it must not, and this is the half of the complaint that shows up
     * "right away" rather than after a couple of minutes — swiping the app away is the one
     * gesture that takes the notification with it instantly, and it is not a request to lose
     * your place. Something still playing is never stopped, whatever the setting says;
     * stopping audio because a task was swiped would be a bug on any setting.
     *
     * Nothing loaded is the one case that always stops: there is no session to be persistent
     * about, and a service left running for an empty player is a battery cost with nothing
     * on screen to explain it.
     */
    fun stopsWhenTaskRemoved(
        persistence: NotificationPersistence,
        phase: PlaybackPhase,
        wantsToPlay: Boolean,
    ): Boolean {
        if (phase == PlaybackPhase.EMPTY) return true
        if (wantsToPlay) return false
        return !persistence.keepsWhilePaused
    }

    /**
     * Whether opening the app should load the last thing played.
     *
     * Only under [NotificationPersistence.ALWAYS_READY]; under the other two, opening the app
     * changes nothing about what is loaded.
     *
     * [wantsToPlay] is the guard against fighting a real request. Somebody who pressed play
     * as the app came up is already having their item resolved, and arming must never
     * overwrite that — a book replaced mid-start by a different one is the worst failure this
     * feature could produce.
     *
     * [PlaybackPhase.IDLE] arms as well as [PlaybackPhase.EMPTY], and it is the cheaper of
     * the two: the item is still in the player and only needs preparing again. It is reached
     * by dismissing the notification, and re-arming after a dismissal is what "always ready"
     * says on the settings screen. A player that is idle *and* wants to play is a failure
     * being retried, so it is left alone.
     */
    fun armsOnOpen(
        persistence: NotificationPersistence,
        phase: PlaybackPhase,
        wantsToPlay: Boolean,
        alreadyArming: Boolean,
    ): Boolean {
        if (persistence != NotificationPersistence.ALWAYS_READY) return false
        if (wantsToPlay || alreadyArming) return false
        return phase == PlaybackPhase.EMPTY || phase == PlaybackPhase.IDLE
    }
}
