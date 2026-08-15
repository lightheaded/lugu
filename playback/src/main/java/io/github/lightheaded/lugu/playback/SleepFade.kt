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
