package io.github.lightheaded.lugu.feature.player

/**
 * The one piece of time arithmetic that belongs to the player rather than to the app.
 *
 * Writing a time down is a shared decision and now lives in `:core:model` alongside every
 * other place a clock or a length is printed — a length that read "1 h 20 min" here and
 * "1h 20m" in a list was the drift that moved it. What stays here is the conversion below,
 * which is not formatting at all: it depends on the speed the player happens to be running
 * at, so it is only meaningful where there is a player.
 */

/**
 * How long it actually takes to reach an audio position at the current speed.
 *
 * Audio seconds are the book's own clock and never move; wall-clock time depends on the
 * speed being used at the time, so this is an estimate for the speed set right now and is
 * only ever shown labelled with it.
 */
internal fun wallClockSecondsAt(audioSec: Double, speed: Float): Double =
    audioSec / speed.coerceAtLeast(0.01f)
