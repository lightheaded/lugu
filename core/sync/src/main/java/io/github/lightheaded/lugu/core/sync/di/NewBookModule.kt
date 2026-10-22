package io.github.lightheaded.lugu.core.sync.di

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.lightheaded.lugu.core.sync.NewBookAnnouncer

/**
 * The announcer is bound in `:core:download`, beside the notifications. Optional here, so
 * a graph without that module, such as a test's, still builds and simply tells nobody.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NewBookModule {
    @BindsOptionalOf
    abstract fun newBookAnnouncer(): NewBookAnnouncer
}
