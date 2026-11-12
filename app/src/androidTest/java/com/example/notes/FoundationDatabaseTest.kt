package com.example.notes

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FoundationDatabaseTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "foundation-${UUID.randomUUID()}.db"
    private val helper = DatabaseHelper(context, name)

    @After
    fun cleanup() {
        helper.close()
        context.deleteDatabase(name)
    }

    private fun seedV1(conflictingTasks: Boolean = false) {
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "title TEXT NOT NULL, content TEXT, timestamp TEXT NOT NULL)")
            db.execSQL("INSERT INTO notes VALUES(7, '  title  ', NULL, '2001-02-03 04:05:06')")
            db.execSQL("INSERT INTO notes VALUES(42, '', ?, '2025-12-31 23:59:59')",
                arrayOf("Preview\n" + "x".repeat(50_000)))
            db.execSQL("INSERT INTO notes VALUES(108, '☐ literal', '\nBody', '1999-01-01 00:00:00')")
            db.execSQL("INSERT INTO notes VALUES(257, 'deleted', '', '2026-01-01 00:00:00')")
            db.execSQL("DELETE FROM notes WHERE id=257")
            if (conflictingTasks) db.execSQL("CREATE TABLE tasks(marker TEXT)")
            db.version = 1
        }
    }

    private fun raw() = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
        SQLiteDatabase.OPEN_READWRITE)

    private fun snapshot(): List<List<String?>> = raw().use { db ->
        buildList {
            add(listOf("version", db.version.toString()))
            for (sql in listOf("SELECT type, name, sql FROM sqlite_master ORDER BY type, name",
                "SELECT id, title, content, timestamp FROM notes ORDER BY id",
                "SELECT name, seq FROM sqlite_sequence ORDER BY name")) {
                db.rawQuery(sql, null).use { c ->
                    while (c.moveToNext()) add((0 until c.columnCount).map {
                        if (c.isNull(it)) null else c.getString(it)
                    })
                }
            }
        }
    }

    private fun notes(): List<List<String?>> = raw().use { db ->
        db.rawQuery("SELECT id, title, content, timestamp FROM notes ORDER BY id", null).use { c ->
            buildList {
                while (c.moveToNext()) add((0 until c.columnCount).map {
                    if (c.isNull(it)) null else c.getString(it)
                })
            }
        }
    }

    @Test
    fun seededUpgradePreservesExactNotesSchemaNullsTimestampsIdsAndSequence() {
        seedV1()
        val before = notes()
        val schema = raw().use { db -> db.rawQuery(
            "SELECT sql FROM sqlite_master WHERE name='notes'", null).use {
            it.moveToFirst(); it.getString(0)
        } }
        helper.writableDatabase.use { db ->
            assertEquals(2, db.version)
            db.rawQuery("PRAGMA foreign_keys", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            db.rawQuery("SELECT sql FROM sqlite_master WHERE name='notes'", null).use {
                it.moveToFirst(); assertEquals(schema, it.getString(0))
            }
        }
        assertEquals(before, notes())
        assertEquals(3, helper.getNotePreviews().size)
        assertEquals("", helper.getNotePreviews().single { it.id == 7L }.content)
        assertEquals("", helper.getNoteById(7L)!!.content) // null stays null in storage
        assertEquals("Preview", helper.getNotePreviews().single { it.id == 42L }.content)
        assertEquals(before[1][2], helper.getNoteById(42L)!!.content)
        assertEquals("", helper.getNotePreviews().single { it.id == 108L }.content)
        assertEquals(258L, helper.insertNote("next", "body"))
        assertNotNull(helper.insertTask("task after upgrade"))
    }

    @Test
    fun freshV2AndExistingNoteCrudRemainCompatible() {
        helper.readableDatabase.use { assertEquals(2, it.version) }
        val id = helper.insertNote("title", "first\nsecond")
        assertEquals(1L, id)
        assertEquals("first", helper.getNotePreviews().single().content)
        assertEquals("first\nsecond", helper.getNoteById(id)!!.content)
        assertEquals(1, helper.updateNote(id, "edited", "body"))
        assertEquals("edited", helper.getNoteById(id)!!.title)
        assertEquals(0, helper.updateNote(900, "missing", "body"))
        assertEquals(1, helper.deleteNotes(listOf(id, id, 900)))
        assertNull(helper.getNoteById(id))
        assertTrue(helper.getNotePreviews().isEmpty())
        assertTrue(helper.getTaskSummaries().isEmpty())
    }

    private fun unsupported(version: Int) = object : SQLiteOpenHelper(context, name, null, version) {
        override fun onCreate(db: SQLiteDatabase) = error("Fixture already exists")
        override fun onConfigure(db: SQLiteDatabase) = helper.onConfigure(db)
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
            helper.onUpgrade(db, oldVersion, newVersion)
    }

    @Test
    fun unsupportedOneToThreeRejectsBeforeAnySql() {
        seedV1()
        val before = snapshot()
        assertThrows(SQLiteException::class.java) { unsupported(3).use { it.writableDatabase } }
        assertEquals(before, snapshot())
    }

    @Test
    fun unsupportedTwoToThreePreservesNotesTasksAndVersion() {
        helper.insertNote("keep", "body")
        val task = helper.insertTask("keep task")!!
        val before = snapshot()
        val taskBefore = helper.getTaskById(task)
        assertThrows(SQLiteException::class.java) { unsupported(3).use { it.writableDatabase } }
        assertEquals(before, snapshot())
        assertEquals(taskBefore, helper.getTaskById(task))
    }

    @Test
    fun invalidUpgradePairsAreRejectedWithoutMutatingOpenDatabase() {
        seedV1()
        val before = snapshot()
        raw().use { db ->
            for ((old, new) in listOf(0 to 2, 2 to 2, 2 to 1, 3 to 2, 1 to 3, 2 to 3)) {
                assertThrows(SQLiteException::class.java) { helper.onUpgrade(db, old, new) }
            }
        }
        assertEquals(before, snapshot())
    }

    @Test
    fun failedMigrationPreservesSchemaAndVersion() {
        seedV1(conflictingTasks = true)
        val before = snapshot()
        assertThrows(SQLiteException::class.java) { helper.writableDatabase }
        assertEquals(before, snapshot()) // No partial tasks table, sequence or version bump.
    }

    @Test
    fun tasksHaveStableIdsCompletionAndSummaries() {
        val task = helper.insertTask("  parent  ")!!
        val other = helper.insertTask("other", true)!!
        assertEquals(1, helper.updateTask(task, "renamed"))
        assertEquals(1, helper.setTaskCompleted(task, true))
        assertEquals(1, helper.setTaskCompleted(task, false))
        val summary = helper.getTaskSummaries().single { it.task.id == task }
        assertEquals("renamed", summary.task.text)
        assertFalse(summary.task.completed)
        assertTrue(summary.task.updatedAt.isNotBlank())
        helper.close()
        DatabaseHelper(context, name).use { reopened ->
            assertEquals("renamed", reopened.getTaskById(task)!!.text)
            assertFalse(reopened.getTaskById(task)!!.completed)
            assertEquals(1, reopened.deleteTask(task))
            assertEquals(0, reopened.deleteTask(task))
            assertNotNull(reopened.getTaskById(other))
        }
        assertEquals(other, helper.getTaskSummaries().single().task.id)
        assertEquals(0, helper.setTaskCompleted(900, true))
        assertEquals(0, helper.updateTask(900, "missing"))
    }

    @Test
    fun blankDraftsAreIgnoredAndChecksAreEnforced() {
        assertNull(helper.insertTask(" \t\r\n\u2003"))
        assertTrue(helper.getTaskSummaries().isEmpty())
        val task = helper.insertTask("parent")!!
        assertEquals(0, helper.updateTask(task, "\n"))
        helper.writableDatabase.use { db ->
            for (sql in listOf("UPDATE tasks SET completed=2", "UPDATE tasks SET text=' '",
                "UPDATE tasks SET text=NULL")) {
                assertThrows(SQLiteException::class.java) { db.execSQL(sql) }
            }
        }
        assertEquals("parent", helper.getTaskById(task)!!.text)
    }

    @Test
    fun batchTaskDeleteCountsRowsAndRollsBackOnInjectedFailure() {
        val a = helper.insertTask("first", true)!!
        val b = helper.insertTask("second", true)!!
        val keep = helper.insertTask("unchecked")!!
        val before = helper.getTaskSummaries()
        helper.writableDatabase.use { db ->
            db.execSQL("CREATE TRIGGER reject_batch_delete BEFORE DELETE ON tasks " +
                "WHEN OLD.id=$b BEGIN SELECT RAISE(ABORT, 'injected batch failure'); END")
        }
        assertThrows(SQLiteException::class.java) { helper.deleteTasks(listOf(a, b)) }
        assertEquals(before, helper.getTaskSummaries())
        helper.writableDatabase.use { it.execSQL("DROP TRIGGER reject_batch_delete") }
        assertEquals(0, helper.deleteTasks(emptyList()))
        assertEquals(2, helper.deleteTasks(listOf(a, b, a, 900)))
        assertEquals(listOf(keep), helper.getTaskSummaries().map { it.task.id })
    }

    @Test
    fun multiNoteDeleteAndTaskDeleteAreAtomicOnInjectedFailure() {
        val a = helper.insertNote("a", "body")
        val b = helper.insertNote("b", "body")
        val task = helper.insertTask("parent")!!
        helper.writableDatabase.use { db ->
            db.execSQL("CREATE TRIGGER reject_note_delete BEFORE DELETE ON notes " +
                "WHEN OLD.id=$b BEGIN SELECT RAISE(ABORT, 'injected note failure'); END")
            db.execSQL("CREATE TRIGGER reject_task_delete BEFORE DELETE ON tasks " +
                "BEGIN SELECT RAISE(ABORT, 'injected delete failure'); END")
        }
        assertThrows(SQLiteException::class.java) { helper.deleteNotes(listOf(a, b)) }
        assertEquals(listOf(a, b), helper.getNotePreviews().map { it.id }.sorted())
        assertThrows(SQLiteException::class.java) { helper.deleteTask(task) }
        assertNotNull(helper.getTaskById(task))
    }
}
