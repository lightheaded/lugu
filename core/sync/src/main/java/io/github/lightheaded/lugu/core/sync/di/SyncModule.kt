package io.github.lightheaded.lugu.core.sync.di

import android.content.Context
import android.os.Build
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.lightheaded.lugu.core.api.AbsClient
import io.github.lightheaded.lugu.core.api.AuthInterceptor
import io.github.lightheaded.lugu.core.api.DeviceInfoDto
import io.github.lightheaded.lugu.core.api.ServerUrlProvider
import io.github.lightheaded.lugu.core.api.TokenStore
import io.github.lightheaded.lugu.core.db.BookmarkDao
import io.github.lightheaded.lugu.core.db.ChapterDao
import io.github.lightheaded.lugu.core.db.CollectionDao
import io.github.lightheaded.lugu.core.db.DownloadDao
import io.github.lightheaded.lugu.core.db.EpisodeDao
import io.github.lightheaded.lugu.core.db.ItemSeriesDao
import io.github.lightheaded.lugu.core.db.LibraryDao
import io.github.lightheaded.lugu.core.db.LibraryItemDao
import io.github.lightheaded.lugu.core.db.LibraryItemFtsDao
import io.github.lightheaded.lugu.core.db.LuguDatabase
import io.github.lightheaded.lugu.core.db.OutboxDao
import io.github.lightheaded.lugu.core.db.PositionHistoryDao
import io.github.lightheaded.lugu.core.db.ProgressDao
import io.github.lightheaded.lugu.core.db.QueueDao
import io.github.lightheaded.lugu.core.db.ServerDao
import io.github.lightheaded.lugu.core.db.SessionLedgerDao
import io.github.lightheaded.lugu.core.sync.ActiveServerUrlProvider
import io.github.lightheaded.lugu.core.sync.Clock
import io.github.lightheaded.lugu.core.sync.EncryptedTokenStore
import io.github.lightheaded.lugu.core.sync.WallClock
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): LuguDatabase = LuguDatabase.build(context)

    @Provides fun serverDao(db: LuguDatabase): ServerDao = db.serverDao()

    @Provides fun libraryDao(db: LuguDatabase): LibraryDao = db.libraryDao()

    @Provides fun libraryItemDao(db: LuguDatabase): LibraryItemDao = db.libraryItemDao()

    @Provides fun itemSeriesDao(db: LuguDatabase): ItemSeriesDao = db.itemSeriesDao()

    @Provides fun episodeDao(db: LuguDatabase): EpisodeDao = db.episodeDao()

    @Provides fun chapterDao(db: LuguDatabase): ChapterDao = db.chapterDao()

    @Provides fun progressDao(db: LuguDatabase): ProgressDao = db.progressDao()

    @Provides fun sessionLedgerDao(db: LuguDatabase): SessionLedgerDao = db.sessionLedgerDao()

    @Provides fun outboxDao(db: LuguDatabase): OutboxDao = db.outboxDao()

    @Provides fun queueDao(db: LuguDatabase): QueueDao = db.queueDao()

    @Provides fun positionHistoryDao(db: LuguDatabase): PositionHistoryDao = db.positionHistoryDao()

    @Provides fun downloadDao(db: LuguDatabase): DownloadDao = db.downloadDao()

    @Provides fun libraryItemFtsDao(db: LuguDatabase): LibraryItemFtsDao = db.libraryItemFtsDao()

    @Provides fun bookmarkDao(db: LuguDatabase): BookmarkDao = db.bookmarkDao()

    @Provides fun collectionDao(db: LuguDatabase): CollectionDao = db.collectionDao()
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun deviceInfo(tokenStore: EncryptedTokenStore, @ApplicationContext context: Context): DeviceInfoDto {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
        return DeviceInfoDto(
            deviceId = tokenStore.deviceId(),
            clientName = "lugu",
            clientVersion = version,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            sdkVersion = Build.VERSION.SDK_INT,
        )
    }

    /**
     * One OkHttp instance for API calls, cover images and media. Sharing it keeps
     * connection pooling and, later, a single place to attach the auth header for the
     * media data source.
     */
    @Provides
    @Singleton
    fun okHttp(serverUrlProvider: ServerUrlProvider, client: dagger.Lazy<AbsClient>): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                AuthInterceptor(serverUrlProvider) { client.get().validAccessToken() },
            )
            .build()

    @Provides
    @Singleton
    fun absClient(
        serverUrlProvider: ServerUrlProvider,
        tokenStore: TokenStore,
        deviceInfo: DeviceInfoDto,
    ): AbsClient = AbsClient(
        serverUrlProvider = serverUrlProvider,
        tokenStore = tokenStore,
        deviceInfo = deviceInfo,
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncBindingsModule {
    @Binds
    abstract fun tokenStore(impl: EncryptedTokenStore): TokenStore

    @Binds
    abstract fun serverUrlProvider(impl: ActiveServerUrlProvider): ServerUrlProvider

    @Binds
    abstract fun clock(impl: WallClock): Clock
}
