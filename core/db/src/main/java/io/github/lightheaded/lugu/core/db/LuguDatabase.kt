package io.github.lightheaded.lugu.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        PositionHistoryEntity::class,
    ],
    version = 2,
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

    abstract fun positionHistoryDao(): PositionHistoryDao

    companion object {
        const val NAME = "lugu.db"

        /**
         * Adds the position history table. Purely additive — no existing row is read,
         * rewritten or dropped, so an upgrade cannot cost anyone their library mirror
         * or their progress.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `position_history` (
                        `rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `serverId` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `libraryItemId` TEXT NOT NULL,
                        `episodeKey` TEXT NOT NULL,
                        `fromSec` REAL NOT NULL,
                        `toSec` REAL NOT NULL,
                        `atMs` INTEGER NOT NULL,
                        `reason` TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_position_history_serverId_userId_libraryItemId_atMs` " +
                        "ON `position_history` (`serverId`, `userId`, `libraryItemId`, `atMs`)",
                )
            }
        }

        fun build(context: Context): LuguDatabase =
            Room.databaseBuilder(context.applicationContext, LuguDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}

/** Books have no episode; SQLite primary keys reject nulls, so the empty string stands in. */
fun episodeKeyOf(episodeId: String?): String = episodeId.orEmpty()

fun String.toEpisodeIdOrNull(): String? = takeIf { it.isNotEmpty() }
