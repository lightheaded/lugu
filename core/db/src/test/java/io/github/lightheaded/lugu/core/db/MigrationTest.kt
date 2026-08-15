package io.github.lightheaded.lugu.core.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A hand-written migration whose DDL disagrees with the entity crashes the app on
 * upgrade — Room validates the schema when it opens the database, and by then the user
 * has already installed the new version over a working one. So the migration's output
 * is compared against the schema Room generates for the same entity, rather than
 * assumed to match.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private fun schemaOf(db: SupportSQLiteDatabase, table: String): String? =
        db.query("SELECT sql FROM sqlite_master WHERE name = ?", arrayOf(table)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    /**
     * Column name, declared type, nullability and primary-key position — the same facts
     * Room compares when it validates a schema on open. Raw CREATE text is the wrong
     * thing to assert on: SQLite ignores whitespace, so a reformatted but identical
     * statement would fail while a genuinely wrong one could pass.
     */
    private fun columnsOf(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    val type = cursor.getString(cursor.getColumnIndexOrThrow("type"))
                    val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull"))
                    val pk = cursor.getInt(cursor.getColumnIndexOrThrow("pk"))
                    add("$name:$type:notnull=$notNull:pk=$pk")
                }
            }
        }.sorted()

    private fun indexedColumnsOf(db: SupportSQLiteDatabase, index: String): List<String> =
        db.query("PRAGMA index_info(`$index`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
        }

    private fun indexesOf(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = ?", arrayOf(table))
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.let { if (!it.startsWith("sqlite_")) add(it) }
                    }
                }
            }

    private fun normalise(sql: String?): String =
        sql.orEmpty().replace(Regex("\\s+"), " ").replace("IF NOT EXISTS ", "").trim()

    /**
     * Rebuilds a database at an older version from the schema Room exported at the time.
     *
     * The exported JSON is the only honest record of what is actually on a user's phone;
     * hand-writing the old DDL in the test would just be a second guess that can drift
     * from the first.
     */
    private fun databaseAtVersion(version: Int): SupportSQLiteDatabase {
        val file = File("schemas/io.github.lightheaded.lugu.core.db.LuguDatabase/$version.json")
        check(file.exists()) { "No exported schema for version $version at ${file.absolutePath}" }

        val entities = JSONObject(file.readText()).getJSONObject("database").getJSONArray("entities")
        val db = blankDatabase()
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))

            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        return db
    }

    private fun blankDatabase(): SupportSQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null) // in-memory
                .callback(callback)
                .build(),
        )
        return helper.writableDatabase
    }

    @Test
    fun `migration 1 to 2 creates the table Room expects`() {
        // What the migration produces, applied to a database that does not have it.
        val migrated = blankDatabase()
        LuguDatabase.MIGRATION_1_2.migrate(migrated)
        val migratedColumns = columnsOf(migrated, "position_history")
        val migratedIndexes = indexesOf(migrated, "position_history").sorted()
        val migratedIndexColumns = migratedIndexes.associateWith { indexedColumnsOf(migrated, it) }
        migrated.close()

        // What Room itself creates for the same entity.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.inMemoryDatabaseBuilder(context, LuguDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        room.openHelper.writableDatabase.let { db ->
            assertThat(migratedColumns).isEqualTo(columnsOf(db, "position_history"))
            assertThat(migratedIndexes).isEqualTo(indexesOf(db, "position_history").sorted())
            migratedIndexes.forEach { index ->
                assertThat(migratedIndexColumns[index]).isEqualTo(indexedColumnsOf(db, index))
            }
        }
        room.close()
    }

    @Test
    fun `migration is safe to run twice`() {
        val db = blankDatabase()
        LuguDatabase.MIGRATION_1_2.migrate(db)
        // Re-running must not throw; a half-applied upgrade retried should converge.
        LuguDatabase.MIGRATION_1_2.migrate(db)
        assertThat(schemaOf(db, "position_history")).isNotNull()
        db.close()
    }

    @Test
    fun `migration 2 to 3 matches the schema Room generates`() {
        val migrated = databaseAtVersion(2)
        LuguDatabase.MIGRATION_2_3.migrate(migrated)

        val tables = listOf("download", "library_item_fts", "library_item")
        val migratedColumns = tables.associateWith { columnsOf(migrated, it) }
        val migratedIndexes = indexesOf(migrated, "download").sorted()
        val migratedIndexColumns = migratedIndexes.associateWith { indexedColumnsOf(migrated, it) }
        // FTS4 declares itself; the virtual-table statement has to agree exactly or Room
        // rejects the database on open, and PRAGMA table_info cannot show that.
        val migratedFtsSql = normalise(schemaOf(migrated, "library_item_fts"))
        migrated.close()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.inMemoryDatabaseBuilder(context, LuguDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        room.openHelper.writableDatabase.let { db ->
            tables.forEach { table ->
                assertThat(migratedColumns[table]).isEqualTo(columnsOf(db, table))
            }
            assertThat(migratedIndexes).isEqualTo(indexesOf(db, "download").sorted())
            migratedIndexes.forEach { index ->
                assertThat(migratedIndexColumns[index]).isEqualTo(indexedColumnsOf(db, index))
            }
            assertThat(migratedFtsSql).isEqualTo(normalise(schemaOf(db, "library_item_fts")))
        }
        room.close()
    }

    /**
     * The point of backfilling in the migration rather than waiting for a resync: someone
     * who upgrades on a train can still search their library.
     */
    @Test
    fun `migration 2 to 3 indexes the library that is already mirrored`() {
        val db = databaseAtVersion(2)
        db.execSQL(
            """
            INSERT INTO library_item (serverId, userId, id, libraryId, mediaType, title, subtitle,
                authorName, narratorName, seriesName, description, durationSec, sizeBytes,
                numEpisodes, addedAtMs, updatedAtMs, coverPath, rawJson, syncedAtMs)
            VALUES ('s', 'u', 'li_1', 'lib_1', 'BOOK', 'Lighthouse Wakes', NULL,
                'James T. R. Corven', 'Jefferson Vale', 'The Breakwater #1', NULL, 100.0, 0,
                0, 0, 0, NULL, NULL, 0)
            """.trimIndent(),
        )

        LuguDatabase.MIGRATION_2_3.migrate(db)

        val hits = db.query(
            "SELECT itemId FROM library_item_fts WHERE library_item_fts MATCH 'corven'",
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertThat(hits).containsExactly("li_1")
        db.close()
    }

    @Test
    fun `migration 2 to 3 is safe to run twice`() {
        val db = databaseAtVersion(2)
        db.execSQL(
            """
            INSERT INTO library_item (serverId, userId, id, libraryId, mediaType, title, subtitle,
                authorName, narratorName, seriesName, description, durationSec, sizeBytes,
                numEpisodes, addedAtMs, updatedAtMs, coverPath, rawJson, syncedAtMs)
            VALUES ('s', 'u', 'li_1', 'lib_1', 'BOOK', 'Lighthouse Wakes', NULL, 'Corven', NULL,
                NULL, NULL, 100.0, 0, 0, 0, 0, NULL, NULL, 0)
            """.trimIndent(),
        )

        LuguDatabase.MIGRATION_2_3.migrate(db)
        // ALTER TABLE ADD COLUMN has no IF NOT EXISTS form and an FTS4 table has no
        // unique constraint, so both are guarded by hand. Re-running has to converge,
        // not throw and not silently double the search index.
        LuguDatabase.MIGRATION_2_3.migrate(db)

        assertThat(columnsOf(db, "library_item").filter { it.startsWith("seriesSequence:") }).hasSize(1)
        assertThat(schemaOf(db, "download")).isNotNull()

        val indexed = db.query("SELECT COUNT(*) FROM library_item_fts").use {
            it.moveToFirst()
            it.getInt(0)
        }
        assertThat(indexed).isEqualTo(1)
        db.close()
    }

    @Test
    fun `migration 3 to 4 matches the schema Room generates`() {
        val migrated = databaseAtVersion(3)
        LuguDatabase.MIGRATION_3_4.migrate(migrated)

        val migratedColumns = columnsOf(migrated, "bookmark")
        val migratedIndexes = indexesOf(migrated, "bookmark").sorted()
        val migratedIndexColumns = migratedIndexes.associateWith { indexedColumnsOf(migrated, it) }
        migrated.close()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.inMemoryDatabaseBuilder(context, LuguDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        room.openHelper.writableDatabase.let { db ->
            assertThat(migratedColumns).isEqualTo(columnsOf(db, "bookmark"))
            assertThat(migratedIndexes).isEqualTo(indexesOf(db, "bookmark").sorted())
            migratedIndexes.forEach { index ->
                assertThat(migratedIndexColumns[index]).isEqualTo(indexedColumnsOf(db, index))
            }
        }
        room.close()
    }

    @Test
    fun `migration 3 to 4 leaves the mirror alone`() {
        val db = databaseAtVersion(3)
        db.execSQL(
            """
            INSERT INTO library_item (serverId, userId, id, libraryId, mediaType, title, subtitle,
                authorName, narratorName, seriesName, seriesTitle, seriesSequence, description,
                durationSec, sizeBytes, numEpisodes, addedAtMs, updatedAtMs, coverPath, rawJson,
                syncedAtMs)
            VALUES ('s', 'u', 'li_1', 'lib_1', 'BOOK', 'Lighthouse Wakes', NULL, NULL, NULL, NULL,
                NULL, NULL, NULL, 100.0, 0, 0, 0, 0, NULL, NULL, 0)
            """.trimIndent(),
        )

        LuguDatabase.MIGRATION_3_4.migrate(db)
        // Re-running must converge rather than throw: one bad upgrade should not turn
        // into a database that can never be opened again.
        LuguDatabase.MIGRATION_3_4.migrate(db)

        val items = db.query("SELECT COUNT(*) FROM library_item").use {
            it.moveToFirst()
            it.getInt(0)
        }
        assertThat(items).isEqualTo(1)
        assertThat(schemaOf(db, "bookmark")).isNotNull()
        db.close()
    }

    @Test
    fun `history rows round-trip and come back newest first`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LuguDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val dao = db.positionHistoryDao()
        fun entry(to: Double, at: Long) = PositionHistoryEntity(
            serverId = "s",
            userId = "u",
            libraryItemId = "li_1",
            episodeKey = "",
            fromSec = 1_000.0,
            toSec = to,
            atMs = at,
            reason = "seek",
        )
        dao.insert(entry(to = 0.0, at = 100))
        dao.insert(entry(to = 500.0, at = 200))

        val rows = dao.observeForItem("s", "u", "li_1").first()

        assertThat(rows).hasSize(2)
        assertThat(rows.first().atMs).isEqualTo(200)
        assertThat(dao.mostRecentForItem("s", "u", "li_1")?.toSec).isEqualTo(500.0)
        db.close()
    }
}
