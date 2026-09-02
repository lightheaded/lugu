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
        ItemSeriesEntity::class,
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
        CollectionEntity::class,
        CollectionItemEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class LuguDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao

    /** Deletes one account's mirror. See [AccountDataDao]. */
    abstract fun accountDataDao(): AccountDataDao

    abstract fun libraryDao(): LibraryDao

    abstract fun libraryItemDao(): LibraryItemDao

    abstract fun itemSeriesDao(): ItemSeriesDao

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

    abstract fun collectionDao(): CollectionDao

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

        /**
         * Adds collections, and a second address for the server.
         *
         * Additive like the rest. The address column is nullable and defaults to null,
         * which is exactly "no second address configured", so an upgraded install behaves
         * as it did until somebody sets one.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("server", "lanBaseUrl")) {
                    db.execSQL("ALTER TABLE `server` ADD COLUMN `lanBaseUrl` TEXT DEFAULT NULL")
                }

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `collection` (
                        `serverId` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `id` TEXT NOT NULL,
                        `libraryId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT,
                        `updatedAtMs` INTEGER NOT NULL,
                        `syncedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`serverId`, `userId`, `id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_collection_serverId_userId_libraryId` " +
                        "ON `collection` (`serverId`, `userId`, `libraryId`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `collection_item` (
                        `serverId` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `collectionId` TEXT NOT NULL,
                        `libraryItemId` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        PRIMARY KEY(`serverId`, `userId`, `collectionId`, `libraryItemId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_collection_item_serverId_userId_libraryItemId` " +
                        "ON `collection_item` (`serverId`, `userId`, `libraryItemId`)",
                )
            }
        }

        /**
         * Adds the series membership table, and seeds it from what the mirror already knows.
         *
         * Additive like the rest — the two series columns on `library_item` stay exactly
         * where they are, still written and still read, so nothing that queries them
         * changes behaviour on the way through this upgrade.
         *
         * The backfill is the part that matters to somebody upgrading. Every query that
         * used to read `library_item.seriesTitle` now reads this table, so an empty table
         * would mean an empty "Next in series" shelf and empty series pages until a sync
         * finished — on a train, indefinitely. Copying the parsed columns across gives an
         * upgraded install exactly the series it had a moment earlier, and the first sync
         * afterwards replaces those rows with the server's own membership. The rows are
         * marked [SeriesOrigin.PARSED] precisely so that replacement is allowed to happen.
         *
         * A book in two series is still one row after the backfill, because one row is all
         * the old columns could hold. That is the thing the next sync fixes, and it is not
         * something SQL could have recovered here.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `item_series` (
                        `serverId` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `libraryItemId` TEXT NOT NULL,
                        `libraryId` TEXT NOT NULL,
                        `seriesName` TEXT NOT NULL,
                        `seriesId` TEXT,
                        `sequence` REAL,
                        `serverRank` INTEGER,
                        `origin` INTEGER NOT NULL,
                        `syncedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`serverId`, `userId`, `libraryItemId`, `seriesName`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_item_series_serverId_userId_seriesName` " +
                        "ON `item_series` (`serverId`, `userId`, `seriesName`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_item_series_serverId_userId_libraryId` " +
                        "ON `item_series` (`serverId`, `userId`, `libraryId`)",
                )

                // INSERT OR REPLACE rather than a plain insert: the primary key makes the
                // backfill idempotent, so a migration retried after a bad upgrade
                // converges instead of failing on a constraint.
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO `item_series`
                        (`serverId`, `userId`, `libraryItemId`, `libraryId`, `seriesName`,
                         `seriesId`, `sequence`, `serverRank`, `origin`, `syncedAtMs`)
                    SELECT `serverId`, `userId`, `id`, `libraryId`, `seriesTitle`,
                           NULL, `seriesSequence`, NULL, ${SeriesOrigin.PARSED}, 0
                    FROM `library_item`
                    WHERE `seriesTitle` IS NOT NULL AND TRIM(`seriesTitle`) != ''
                    """.trimIndent(),
                )
            }
        }

        /**
         * Splits one progress column that held two clocks.
         *
         * `serverLastUpdateMs` was written from whichever side last touched the row: the
         * server's own `lastUpdate` when a copy was adopted, and this device's
         * `System.currentTimeMillis()` when a push was accepted. Conflict resolution then
         * compared it against the server's stamp, so on a device whose clock ran behind
         * the server's a stale server position won every conflict — which is how a
         * resumed book came back thirty seconds behind where it was left.
         *
         * The two new columns carry what a push told us: the position the server
         * accepted, and the finished flag that went with it. `-1` means "the server has
         * accepted nothing from this device", which is the honest state for every row
         * that already exists, because what the old column holds cannot be attributed to
         * either clock after the fact.
         *
         * Nothing is lost by that. A row with no recorded push is resolved on the server
         * stamp alone, which is the rule the next pull re-establishes: the first
         * `startSession` for an item reads the server's copy and stores its real stamp.
         * The cost is bounded and it is stated here — until that first read, a row that
         * disagrees with the server by more than fifteen seconds adopts the server's
         * position, with the undo the UI already offers for an adopted jump.
         *
         * `serverLastUpdateMs` is kept and its meaning narrowed to the server's clock.
         * Existing values may be either clock, so they are cleared: 0 reads as "no copy
         * of this row has been seen", which is true of a value nobody can attribute.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("progress", "pushedTimeSec")) {
                    db.execSQL(
                        "ALTER TABLE `progress` ADD COLUMN `pushedTimeSec` REAL NOT NULL DEFAULT -1.0",
                    )
                }
                if (!db.hasColumn("progress", "pushedFinished")) {
                    db.execSQL(
                        "ALTER TABLE `progress` ADD COLUMN `pushedFinished` INTEGER NOT NULL DEFAULT 0",
                    )
                }
                db.execSQL("UPDATE `progress` SET `serverLastUpdateMs` = 0")
            }
        }

        fun build(context: Context): LuguDatabase =
            Room.databaseBuilder(context.applicationContext, LuguDatabase::class.java, NAME)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                )
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
