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
        DownloadEntity::class,
        LibraryItemFtsEntity::class,
        BookmarkEntity::class,
    ],
    version = 4,
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

    abstract fun downloadDao(): DownloadDao

    abstract fun libraryItemFtsDao(): LibraryItemFtsDao

    abstract fun bookmarkDao(): BookmarkDao

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

        /**
         * Adds downloads, the full-text index and the parsed series sequence.
         *
         * Additive again — no existing row is rewritten, so an upgrade cannot cost a
         * library mirror or a position. Two deliberate choices about backfill:
         *
         *  - The FTS index is populated here from the rows already in the mirror, so
         *    search works the moment the app opens rather than after a full resync.
         *  - `seriesTitle` and `seriesSequence` are left null and filled by the next
         *    library sync. Splitting "The Breakwater #2" in SQL is possible and unpleasant;
         *    the mirror re-syncs on every app open, so the series shelf is populated
         *    within seconds and the alternative would be a fragile one-off written in
         *    SQLite string functions.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Every other statement here is idempotent through IF NOT EXISTS; ALTER
                // TABLE has no such form, so it is guarded explicitly. A migration that
                // cannot survive being re-run is a migration that turns one bad upgrade
                // into a permanently unopenable database.
                if (!db.hasColumn("library_item", "seriesTitle")) {
                    db.execSQL("ALTER TABLE `library_item` ADD COLUMN `seriesTitle` TEXT DEFAULT NULL")
                }
                if (!db.hasColumn("library_item", "seriesSequence")) {
                    db.execSQL("ALTER TABLE `library_item` ADD COLUMN `seriesSequence` REAL DEFAULT NULL")
                }

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `download` (
                        `serverId` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `libraryItemId` TEXT NOT NULL,
                        `episodeKey` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `author` TEXT,
                        `mediaType` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `tracksJson` TEXT NOT NULL,
                        `durationSec` REAL NOT NULL,
                        `bytesTotal` INTEGER NOT NULL,
                        `bytesDownloaded` INTEGER NOT NULL,
                        `percent` REAL NOT NULL,
                        `requestedAtMs` INTEGER NOT NULL,
                        `completedAtMs` INTEGER NOT NULL,
                        `error` TEXT,
                        PRIMARY KEY(`serverId`, `userId`, `libraryItemId`, `episodeKey`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_download_serverId_userId_state` " +
                        "ON `download` (`serverId`, `userId`, `state`)",
                )

                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `library_item_fts` USING FTS4(" +
                        "`serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, `itemId` TEXT NOT NULL, " +
                        "`libraryId` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                        "notindexed=`serverId`, notindexed=`userId`, notindexed=`itemId`, " +
                        "notindexed=`libraryId`)",
                )
                // An FTS4 table has no unique constraint, so a re-run would double every
                // row and with it every search result. Clearing first makes the backfill
                // idempotent.
                db.execSQL("DELETE FROM `library_item_fts`")
                db.execSQL(
                    """
                    INSERT INTO `library_item_fts` (`serverId`, `userId`, `itemId`, `libraryId`, `text`)
                    SELECT `serverId`, `userId`, `id`, `libraryId`,
                           `title` || ' ' || COALESCE(`subtitle`, '') || ' ' ||
                           COALESCE(`authorName`, '') || ' ' || COALESCE(`narratorName`, '') || ' ' ||
                           COALESCE(`seriesName`, '') || ' ' || COALESCE(`description`, '')
                    FROM `library_item`
                    """.trimIndent(),
                )
            }
        }

        /**
         * Adds bookmarks. Additive, like the two before it.
         *
         * No backfill: the server is the source of truth for bookmarks made on other
         * devices, and the first pull after an upgrade fills the table. Nothing local
         * can be lost, because nothing local existed.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `bookmark` (
                        `serverId` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `libraryItemId` TEXT NOT NULL,
                        `timeSec` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `isDirty` INTEGER NOT NULL DEFAULT 0,
                        `isDeleted` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`serverId`, `userId`, `libraryItemId`, `timeSec`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_bookmark_serverId_userId_libraryItemId` " +
                        "ON `bookmark` (`serverId`, `userId`, `libraryItemId`)",
                )
            }
        }

        fun build(context: Context): LuguDatabase =
            Room.databaseBuilder(context.applicationContext, LuguDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}

private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean =
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        if (nameIndex < 0) return false
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) return true
        }
        false
    }

/** Books have no episode; SQLite primary keys reject nulls, so the empty string stands in. */
fun episodeKeyOf(episodeId: String?): String = episodeId.orEmpty()

fun String.toEpisodeIdOrNull(): String? = takeIf { it.isNotEmpty() }
