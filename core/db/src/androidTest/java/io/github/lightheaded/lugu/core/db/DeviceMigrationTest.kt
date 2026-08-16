package io.github.lightheaded.lugu.core.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Catches a migration that only works on the SQLite the JVM happens to have.
 *
 * There are already migration tests on the JVM, and they check the thing that matters
 * most — that a hand-written migration produces the schema Room will validate against on
 * open. What they cannot check is the engine. Robolectric links a desktop SQLite build;
 * a phone links Android's own, which differs in which pragmas it honours, in how
 * `ALTER TABLE` behaves, and in what the FTS4 module was compiled with. A migration that
 * passes on the JVM and fails here is an app that will not open after an update, and the
 * user has already installed the new version by the time it happens.
 *
 * This runs the chain the way a real upgrade does — 1 to 2 to 3 to 4 to 5 to 6, one database,
 * each step over the last — against the schemas Room exported at the time, which are the
 * only honest record of what is on a phone that has not been updated in a while. *
 * The method names here are underscored rather than the backticked sentences the JVM suites
 * use. A name with spaces in it needs DEX version 040, which needs minSdk 30; lugu's minSdk
 * is 26, so the test APK will not dex with them.
 */
@RunWith(AndroidJUnit4::class)
class DeviceMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LuguDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    /**
     * The upgrade path in one go, with a row in it.
     *
     * A row rather than an empty database, because the failures worth catching are the
     * ones where a table is rebuilt: an empty table copies cleanly whatever the DDL says,
     * and a column list that does not line up only throws once there is something to move.
     */
    @Test
    fun every_migration_in_turn_opens_on_real_SQLite() {
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO library_item (serverId, userId, id, libraryId, mediaType, title,
                    subtitle, authorName, narratorName, seriesName, description, durationSec,
                    sizeBytes, numEpisodes, addedAtMs, updatedAtMs, coverPath, rawJson, syncedAtMs)
                VALUES ('s', 'u', 'li_1', 'lib_1', 'BOOK', 'The Lighthouse Wakes', NULL,
                    'James T. R. Corven', 'Jefferson Vale', 'The Breakwater #1', NULL, 41400.0, 0,
                    0, 0, 0, NULL, NULL, 0)
                """.trimIndent(),
            )
        }

        // Every step separately, so a failure names the migration that broke rather than
        // "somewhere between 1 and 6".
        for (target in 2..LATEST_VERSION) {
            helper.runMigrationsAndValidate(DB_NAME, target, true, *MIGRATIONS).close()
        }

        // Room validates the schema itself on open; this is the row surviving the trip.
        helper.runMigrationsAndValidate(DB_NAME, LATEST_VERSION, true, *MIGRATIONS)
            .use { db ->
                val titles = db.query("SELECT title FROM library_item").use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                assertThat(titles).containsExactly("The Lighthouse Wakes")
            }
    }

    /**
     * The search index, which is the one thing here that a different SQLite build can
     * refuse outright.
     *
     * FTS4 is a compile-time option. If Android's SQLite ever shipped without it, or with
     * a different default tokeniser, the migration that creates the index would fail on a
     * phone and pass everywhere else — and the failure would be an app that cannot open.
     */
    @Test
    fun the_full_text_index_is_usable_on_real_SQLite() {
        helper.createDatabase(DB_NAME, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO library_item (serverId, userId, id, libraryId, mediaType, title,
                    subtitle, authorName, narratorName, seriesName, description, durationSec,
                    sizeBytes, numEpisodes, addedAtMs, updatedAtMs, coverPath, rawJson, syncedAtMs)
                VALUES ('s', 'u', 'li_1', 'lib_1', 'BOOK', 'The Lighthouse Wakes', NULL,
                    'James T. R. Corven', NULL, NULL, NULL, 41400.0, 0, 0, 0, 0, NULL, NULL, 0)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(DB_NAME, LATEST_VERSION, true, *MIGRATIONS)
            .use { db ->
                val hits = db.query(
                    "SELECT itemId FROM library_item_fts WHERE library_item_fts MATCH 'corven'",
                ).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                assertThat(hits).containsExactly("li_1")
            }
    }

    /**
     * The upgrade an existing install actually performs, and the thing it must not cost.
     *
     * Every query behind "Next in series" and the series pages reads the membership table
     * from version 6 on. Somebody upgrading has a mirror full of items and no memberships,
     * so without the backfill their series shelf would be empty until a sync finished —
     * and on a train that is never. This is that backfill, on the SQLite a phone has.
     */
    @Test
    fun an_upgraded_install_keeps_the_series_it_already_had() {
        helper.createDatabase(DB_NAME, 5).use { db ->
            db.execSQL(
                """
                INSERT INTO library_item (serverId, userId, id, libraryId, mediaType, title,
                    subtitle, authorName, narratorName, seriesName, seriesTitle, seriesSequence,
                    description, durationSec, sizeBytes, numEpisodes, addedAtMs, updatedAtMs,
                    coverPath, rawJson, syncedAtMs)
                VALUES ('s', 'u', 'li_1', 'lib_1', 'BOOK', 'The Lighthouse Wakes', NULL,
                    'James T. R. Corven', 'Jefferson Vale', 'The Breakwater #1', 'The Breakwater',
                    1.0, NULL, 41400.0, 0, 0, 0, 0, NULL, NULL, 0)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(DB_NAME, LATEST_VERSION, true, *MIGRATIONS).use { db ->
            val names = db.query(
                "SELECT seriesName, sequence, origin FROM item_series WHERE libraryItemId = 'li_1'",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(listOf(cursor.getString(0), cursor.getDouble(1), cursor.getInt(2)))
                    }
                }
            }
            assertThat(names)
                .containsExactly(listOf("The Breakwater", 1.0, SeriesOrigin.PARSED))
        }
    }

    private companion object {
        const val DB_NAME = "migration-test.db"

        /**
         * Repeated from the `@Database` annotation because the annotation's value is not
         * readable at runtime. A mismatch fails loudly the moment a new migration is added
         * without being listed below, which is the point.
         */
        const val LATEST_VERSION = 6

        val MIGRATIONS = arrayOf(
            LuguDatabase.MIGRATION_1_2,
            LuguDatabase.MIGRATION_2_3,
            LuguDatabase.MIGRATION_3_4,
            LuguDatabase.MIGRATION_4_5,
            LuguDatabase.MIGRATION_5_6,
        )
    }
}
