package io.github.lightheaded.lugu.playback

/**
 * The volume curve the sleep timer rides down.
 *
 * Cutting the audio dead is what wakes people up, which defeats the point of a sleep
 * timer entirely. The fade has to reach silence at the moment the timer expires and be
 * inaudible as a *change* on the way there.
 *
 * The curve is the square of the remaining fraction rather than the fraction itself.
 * Perceived loudness follows amplitude with a strong compression, so a linear amplitude
 * ramp sounds like nothing happens for most of the fade and then the sound falls off a
 * cliff in the last seconds. Squaring moves the loss forward, which is heard as an even
 * decline — and it starts quietly enough that someone already asleep does not notice it
 * begin.
 */
object SleepFade {

    /**
     * Volume multiplier for [remainingSec] playback seconds left, fading over
     * [fadeSeconds].
     *
     * A fade of zero returns full volume right up to the end, which is the honest reading
     * of "no fade": stop abruptly, as the setting says.
     */
    fun volumeFor(remainingSec: Double?, fadeSeconds: Int): Float {
        if (remainingSec == null || fadeSeconds <= 0) return 1.0f
        if (remainingSec >= fadeSeconds) return 1.0f
        val fraction = (remainingSec / fadeSeconds).coerceIn(0.0, 1.0)
        return (fraction * fraction).toFloat()
    }
}

/**
 * What one tick of the sleep timer must do to the player.
 *
 * The service holds a player that no unit test can build, so the decision is taken here and
 * the service only carries it out. What the decision covers is the part that can be wrong:
 * the volume to write now, and whether this tick is the one that stops the book.
 *
 * [volumeAfterStop] is the value that saves the next morning. A fade ends at silence, and
 * the player keeps the last volume written to it. So a stop that writes nothing back leaves
 * a player that plays nothing, which is the commonest way a fade-out looks like a broken
 * app. It is a property of the decision rather than a line in the service, so a test can
 * assert it.
 */
data class SleepTick(val volume: Float, val stops: Boolean) {

    /** True while the fade is under way, which is what the timer's own display reads. */
    val isFading: Boolean get() = volume < 1.0f

    /** The volume the player must be left at, once the book has stopped. */
    val volumeAfterStop: Float get() = 1.0f
}

/**
 * The decision for one tick, from the time left and the settings in force.
 *
 * Pure, so the loop can be run in a test at any speed and any fade length. See
 * `SleepTimerRestoreTest`.
 */
fun sleepTickFor(remainingSec: Double, fadeSeconds: Int, tickMs: Long, speed: Float): SleepTick =
    SleepTick(
        volume = SleepFade.volumeFor(remainingSec, fadeSeconds),
        stops = SleepCountdown.isDue(remainingSec, tickMs, speed),
    )
