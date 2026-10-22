package io.github.lightheaded.lugu.playback

/**
 * Whether a position write is sent to the server now, or waits for the next pause.
 *
 * Every write lands in the outbox, and only a run of the outbox worker sends it. The
 * service used to start that run on a pause and nowhere else. A write that no pause
 * follows therefore stayed in the outbox until the listener next pressed play and then
 * pause, which can be days later or never. The ordinary case is "ask before a
 * suggestion": a book ends, the next volume is cued and does not start, and the write
 * for the cued volume is the last thing that happens. An instrumented run found it as a
 * push that was still waiting after a minute with nothing left to send it.
 *
 * So the rule is about what comes next, not about the reason. While the player plays,
 * a pause will follow and carry the write out with it, so nothing is started. When the
 * player does not play, no pause is coming, so the write starts its own run. The 5-second
 * tick is the one exception: it only fires while playing, and a push every tick is what
 * the outbox's one-entry-per-item rule exists to avoid.
 */
internal object ProgressPushRule {

    const val TICK = "tick"

    fun pushesNow(reason: String, isPlaying: Boolean): Boolean = reason != TICK && !isPlaying
}
