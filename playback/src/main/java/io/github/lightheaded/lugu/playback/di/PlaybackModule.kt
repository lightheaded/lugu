package io.github.lightheaded.lugu.playback.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.lightheaded.lugu.playback.DefaultResumptionResolver
import io.github.lightheaded.lugu.playback.ResumptionResolver

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {
    @Binds
    abstract fun resumptionResolver(impl: DefaultResumptionResolver): ResumptionResolver
}
