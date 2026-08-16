package io.github.lightheaded.lugu.playback

/** What a change in car connection does to an armed sleep timer. */
enum class SleepTimerCarAction {
    /** Leave it exactly as it is. */
    NOTHING,

    /** Stop counting it down, keeping the mode so it can be taken up again. */
    SUSPEND,

    /** Take it up again, from where the book has since reached. */
    RELEASE,
}

/**
 * A sleep timer must not fire while somebody is driving (upstream app#1478).
 *
 * The timer exists to stop a book playing to a sleeping listener. In a car it does the
 * opposite of what it is for: the audio stops halfway through a journey, and the person it
 * stopped for is the one holding the steering wheel and least able to do anything about it.
 *
 * ### Suspended, not refused and not cancelled
 *
 * Of the three honest answers this is the only reversible one.
 *
 *  - *Refusing* to arm means the phone silently ignores something the listener has just
 *    asked for, and leaves them nothing to undo. It also fails on the ordinary case: a
 *    timer armed in bed the night before is still armed when the car connects in the
 *    morning, so refusing at arming time does not prevent the thing it is for.
 *  - *Cancelling* throws away the setting. The car is a passing condition — the drive ends,
 *    the phone goes back in a pocket, and the timer that was wanted is gone with no record
 *    of it having been there.
 *  - *Suspending* holds the mode, stops the countdown, and gives it back when the car
 *    disconnects. Nothing is lost and nothing fires on the motorway.
 *
 * Taking it up again re-arms from the book's *current* position rather than resuming the
 * old countdown. A thirty-minute timer suspended through a forty-minute drive would
 * otherwise be long overdue and would stop the audio the instant the engine did — which is
 * exactly the surprise this rule exists to prevent, arriving one moment later.
 */
object CarSleepRule {

    /**
     * @param inCar whether a car is connected now.
     * @param armed whether a timer is set.
     * @param suspended whether this rule is already holding one.
     */
    fun actionFor(inCar: Boolean, armed: Boolean, suspended: Boolean): SleepTimerCarAction = when {
        inCar && armed && !suspended -> SleepTimerCarAction.SUSPEND

        // Released even when nothing is armed any more: the listener may have cancelled the
        // timer during the drive, and the hold has to come off with it or the next timer
        // they set would never count down.
        !inCar && suspended -> SleepTimerCarAction.RELEASE

        else -> SleepTimerCarAction.NOTHING
    }
}
