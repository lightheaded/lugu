package io.github.lightheaded.lugu.core.model

/**
 * Addresses in the server's own web client.
 *
 * lugu does not do everything Audiobookshelf does, and pretending otherwise by hiding the
 * thing that does is the worse of the two options. So until there is parity, anything lugu
 * cannot do has somewhere to go: the same server, the same account, in a browser.
 *
 * ## What does not travel
 *
 * This hands an address to another app, and that is the whole caveat. A browser has its own
 * cookies, so somebody who has never signed in there will land on the login page — annoying,
 * but recoverable and obvious.
 *
 * The two that are neither obvious nor recoverable are lugu's own connection settings: a
 * custom header for an identity-aware proxy, and a client certificate for mTLS. Both live in
 * this app and cannot be attached to somebody else's request, so for a server behind either,
 * the browser is refused before it reaches Audiobookshelf at all — and the refusal comes from
 * the proxy, in the proxy's words, which will not mention lugu. Every entry point says so
 * before it is used, because a link that fails this way looks like a broken link.
 */
object WebClient {

    /** The web client's own front page — the library, as the browser last left it. */
    fun home(baseUrl: String): String = baseUrl.trimEnd('/')

    /**
     * One item's page.
     *
     * `/item/:id` read from the server's own client rather than guessed — it is the `_id`
     * route under `client/pages/item` in the Audiobookshelf source. Worth checking rather than
     * assuming, because a wrong link here fails silently: it opens a browser on a page that is
     * not there and reports nothing back to lugu.
     *
     * An episode has no page of its own: Audiobookshelf shows episodes on the podcast's item
     * page, which is the right destination anyway — somebody sent here for one episode almost
     * always wants the feed's controls rather than that episode's row.
     */
    fun item(baseUrl: String, itemId: String): String = "${home(baseUrl)}/item/$itemId"
}
