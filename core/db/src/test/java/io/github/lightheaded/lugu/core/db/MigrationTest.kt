package io.github.lightheaded.lugu.core.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
