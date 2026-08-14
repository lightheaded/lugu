package io.github.lightheaded.lugu.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ServerEntity::class,
        LibraryEntity::class,
        LibraryItemEntity::class,
        EpisodeEntity::class,
        ChapterEntity::class,
        ProgressEntity::class,
        SessionLedgerEntity::class,
        OutboxEntity::class,
        QueueEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class LuguDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao

    abstract fun libraryDao(): LibraryDao

    abstract fun libraryItemDao(): LibraryItemDao

    abstract fun episodeDao(): EpisodeDao

    abstract fun chapterDao(): ChapterDao

    abstract fun progressDao(): ProgressDao

    abstract fun sessionLedgerDao(): SessionLedgerDao

    abstract fun outboxDao(): OutboxDao

    abstract fun queueDao(): QueueDao

    companion object {
        const val NAME = "lugu.db"

        fun build(context: Context): LuguDatabase =
            Room.databaseBuilder(context.applicationContext, LuguDatabase::class.java, NAME)
                .build()
    }
}

/** Books have no episode; SQLite primary keys reject nulls, so the empty string stands in. */
fun episodeKeyOf(episodeId: String?): String = episodeId.orEmpty()

fun String.toEpisodeIdOrNull(): String? = takeIf { it.isNotEmpty() }
