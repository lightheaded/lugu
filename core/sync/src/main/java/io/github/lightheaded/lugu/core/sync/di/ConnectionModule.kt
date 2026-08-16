package io.github.lightheaded.lugu.core.sync.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.lightheaded.lugu.core.api.ConnectionProbe
import io.github.lightheaded.lugu.core.api.ConnectionRace
import javax.inject.Singleton

/**
 * The pieces behind the second address.
 *
 * Separate from the network module rather than folded into it, because these two are the
 * only things in the graph that are allowed to make a request in order to decide something
 * — and keeping them visible is the point. Nothing else in lugu asks whether the network
 * works before using it.
 */
@Module
@InstallIn(SingletonComponent::class)
object ConnectionModule {

    /**
     * Its own short timeouts, so an address that accepts a connection and then says nothing
     * costs the race its deadline rather than the operating system's.
     */
    @Provides
    @Singleton
    fun connectionProbe(): ConnectionProbe = ConnectionProbe()

    /**
     * One race for the whole process. A per-caller instance would mean every screen and
     * the playback service each probing on their own schedule, which is the cost this is
     * meant to avoid.
     */
    @Provides
    @Singleton
    fun connectionRace(probe: ConnectionProbe): ConnectionRace =
        ConnectionRace(probe = { address, headers -> probe.reachable(address, headers) })
}
