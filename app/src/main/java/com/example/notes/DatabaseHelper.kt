// DatabaseHelper.kt
package com.example.notes

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper

class DatabaseHelper(
    context: Context,
    databaseName: String = DATABASE_NAME
) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "EInkNotes.db"
        private const val DATABASE_VERSION = 2

        // Notes table
        private const val TABLE_NOTES = "notes"
        private const val COLUMN_ID = "id"
        private const val COLUMN_TITLE = "title"
        private const val COLUMN_CONTENT = "content"
        private const val COLUMN_TIMESTAMP = "timestamp"
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Create notes table
        val createNotesTable = """
            CREATE TABLE $TABLE_NOTES (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_TITLE TEXT NOT NULL,
                $COLUMN_CONTENT TEXT,
                $COLUMN_TIMESTAMP TEXT NOT NULL
            )
        """.trimIndent()

        db.execSQL(createNotesTable)
        createTaskTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion != 1 || newVersion != 2) {
            throw SQLiteException("No migration from $oldVersion to $newVersion; notes were not changed")
        }
        // SQLiteOpenHelper wraps this callback and the version change in one transaction.
        createTaskTables(db)
    }

    // Note operations
    fun insertNote(title: String, content: String = ""): Long {
        return writeTask { db ->
            val values = ContentValues().apply {
                put(COLUMN_TITLE, title); put(COLUMN_CONTENT, content)
                put(COLUMN_TIMESTAMP, nextNoteTimestamp(db))
            }
            db.insertOrThrow(TABLE_NOTES, null, values)
        }
    }

    // List-only projections: at most 200 characters plus an ellipsis; editor reads stay lossless.
    fun getNotePreviews(): List<Note> {
        val notesList = mutableListOf<Note>()
        val db = this.readableDatabase
        val query = """
            SELECT $COLUMN_ID,
                substr($COLUMN_TITLE, 1, 200) || CASE WHEN length($COLUMN_TITLE) > 200 THEN '…' ELSE '' END AS $COLUMN_TITLE,
                substr(coalesce($COLUMN_CONTENT, ''), 1,
                    min(200, CASE WHEN instr($COLUMN_CONTENT, char(10)) > 0
                        THEN instr($COLUMN_CONTENT, char(10)) - 1 ELSE length(coalesce($COLUMN_CONTENT, '')) END)) ||
                    CASE WHEN (CASE WHEN instr($COLUMN_CONTENT, char(10)) > 0
                        THEN instr($COLUMN_CONTENT, char(10)) - 1 ELSE length(coalesce($COLUMN_CONTENT, '')) END) > 200
                        THEN '…' ELSE '' END AS $COLUMN_CONTENT,
                $COLUMN_TIMESTAMP
            FROM $TABLE_NOTES ORDER BY $COLUMN_TIMESTAMP DESC, $COLUMN_ID DESC
        """.trimIndent()
        val cursor = db.rawQuery(query, null)

        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID))
                val title = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TITLE))
                val content = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CONTENT)) ?: ""
                val timestamp = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP))

                notesList.add(Note(id, title, content, timestamp))
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return notesList
    }

    fun getNoteById(id: Long): Note? {
        val db = this.readableDatabase
        val cursor = db.query(
            TABLE_NOTES,
            arrayOf(COLUMN_ID, COLUMN_TITLE, COLUMN_CONTENT, COLUMN_TIMESTAMP),
            "$COLUMN_ID=?",
            arrayOf(id.toString()),
            null, null, null
        )

        var note: Note? = null
        if (cursor.moveToFirst()) {
            val title = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TITLE))
            val content = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CONTENT)) ?: ""
            val timestamp = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP))
            note = Note(id, title, content, timestamp)
        }
        cursor.close()
        db.close()
        return note
    }

    fun updateNote(id: Long, title: String, content: String): Int {
        return writeTask { db ->
            val values = ContentValues().apply {
                put(COLUMN_TITLE, title); put(COLUMN_CONTENT, content)
                put(COLUMN_TIMESTAMP, nextNoteTimestamp(db))
            }
            db.update(TABLE_NOTES, values, "$COLUMN_ID=?", arrayOf(id.toString()))
        }
    }

    fun deleteNotes(noteIds: List<Long>): Int {
        val db = this.writableDatabase
        var deletedCount = 0

        try {
            db.beginTransaction()
            try {
                for (id in noteIds) {
                    deletedCount += db.delete(TABLE_NOTES, "$COLUMN_ID=?", arrayOf(id.toString()))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            return deletedCount
        } finally {
            db.close()
        }
    }

    private fun createTaskTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE tasks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                text TEXT NOT NULL CHECK(length(trim(text, char(9)||char(10)||char(13)||' ')) > 0),
                completed INTEGER NOT NULL DEFAULT 0 CHECK(completed IN (0, 1)),
                updated_at TEXT NOT NULL
            )
        """.trimIndent())
    }

    // Blank drafts are ignored; original nonblank text is stored without normalization.
    fun insertTask(text: String, completed: Boolean = false): Long? {
        if (text.isBlank()) return null
        return writeTask { db ->
            db.insertOrThrow("tasks", null, ContentValues().apply {
                put("text", text)
                put("completed", completed)
                put("updated_at", nextTaskTimestamp(db))
            })
        }
    }

    fun getTaskById(id: Long): TaskItem? = readableDatabase.use { db ->
        db.rawQuery("SELECT id, text, completed, updated_at FROM tasks WHERE id=?",
            arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst()) TaskItem(cursor.getLong(0), cursor.getString(1),
                cursor.getInt(2) != 0, cursor.getString(3)) else null
        }
    }

    fun getTaskSummaries(): List<TaskSummary> = readableDatabase.use { db ->
        db.rawQuery("""
            SELECT id, text, completed, updated_at FROM tasks
            ORDER BY completed ASC, updated_at DESC, id DESC
        """.trimIndent(), null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(TaskSummary(
                    TaskItem(cursor.getLong(0), cursor.getString(1), cursor.getInt(2) != 0,
                        cursor.getString(3))))
            }
        }
    }

    fun updateTask(id: Long, text: String): Int {
        if (text.isBlank()) return 0
        return writeTask { db ->
            db.update("tasks", ContentValues().apply {
                put("text", text)
                put("updated_at", nextTaskTimestamp(db))
            }, "id=?", arrayOf(id.toString()))
        }
    }

    fun setTaskCompleted(id: Long, completed: Boolean): Int = writeTask { db ->
        db.update("tasks", ContentValues().apply {
            put("completed", completed)
            put("updated_at", nextTaskTimestamp(db))
        }, "id=?", arrayOf(id.toString()))
    }

    fun deleteTask(id: Long): Int = writableDatabase.use { db ->
        db.delete("tasks", "id=?", arrayOf(id.toString()))
    }

    fun deleteTasks(ids: List<Long>): Int = writeTask { db ->
        ids.sumOf { db.delete("tasks", "id = ?", arrayOf(it.toString())) }
    }

    /** Commit one editor snapshot without changing its identity. */
    fun saveTaskDraft(draft: TaskDraft): TaskDraft {
        require(draft.text.isNotBlank()) { "Task text cannot be blank" }
        return writeTask { db ->
            val values = ContentValues().apply {
                put("text", draft.text)
                put("completed", draft.completed)
                put("updated_at", nextTaskTimestamp(db))
            }
            val taskId = draft.id ?: db.insertOrThrow("tasks", null, values)
            if (draft.id != null) check(db.update("tasks", values, "id=?",
                arrayOf(taskId.toString())) == 1) { "Task no longer exists" }
            draft.copy(id = taskId)
        }
    }

    /** Serialize timestamp allocation and write; same-millisecond uncheck is strictly newest. */
    private fun <T> writeTask(action: (SQLiteDatabase) -> T): T = writableDatabase.use { db ->
        db.beginTransaction()
        try {
            val result = action(db)
            db.setTransactionSuccessful()
            result
        } finally { db.endTransaction() }
    }

    // Task-only logical milliseconds; existing second-precision rows remain sortable. Notes unchanged.
    private fun nextTaskTimestamp(db: SQLiteDatabase): String = db.rawQuery("""
        SELECT CASE WHEN max(updated_at) >= strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime')
            THEN strftime('%Y-%m-%d %H:%M:%f', max(updated_at), '+0.001 seconds')
            ELSE strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime') END FROM tasks
    """.trimIndent(), null).use { it.moveToFirst(); checkNotNull(it.getString(0)) }

    // Locale-neutral logical milliseconds; never rewrite legacy rows.
    private fun nextNoteTimestamp(db: SQLiteDatabase): String = db.rawQuery("""
        SELECT CASE WHEN max(timestamp) >= strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime')
            THEN strftime('%Y-%m-%d %H:%M:%f', max(timestamp), '+0.001 seconds')
            ELSE strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime') END FROM notes
    """.trimIndent(), null).use { it.moveToFirst(); checkNotNull(it.getString(0)) }
}
