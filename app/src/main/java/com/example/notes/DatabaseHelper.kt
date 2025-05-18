package com.example.notes

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.*

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "EInkNotes.db"
        private const val DATABASE_VERSION = 1

        // Notes table
        private const val TABLE_NOTES = "notes"
        private const val COLUMN_ID = "id"
        private const val COLUMN_TITLE = "title"
        private const val COLUMN_CONTENT = "content"
        private const val COLUMN_TIMESTAMP = "timestamp"

        // Tasks table
        private const val TABLE_TASKS = "tasks"
        private const val COLUMN_TASK_ID = "id"
        private const val COLUMN_NOTE_ID = "note_id"
        private const val COLUMN_TASK_TEXT = "task_text"
        private const val COLUMN_IS_CHECKED = "is_checked"
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

        // Create tasks table
        val createTasksTable = """
            CREATE TABLE $TABLE_TASKS (
                $COLUMN_TASK_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_NOTE_ID INTEGER NOT NULL,
                $COLUMN_TASK_TEXT TEXT NOT NULL,
                $COLUMN_IS_CHECKED INTEGER DEFAULT 0,
                FOREIGN KEY ($COLUMN_NOTE_ID) REFERENCES $TABLE_NOTES($COLUMN_ID) ON DELETE CASCADE
            )
        """.trimIndent()

        db.execSQL(createNotesTable)
        db.execSQL(createTasksTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_TASKS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NOTES")
        onCreate(db)
    }

    // Note operations
    fun insertNote(title: String, content: String = ""): Long {
        val db = this.writableDatabase
        val values = ContentValues()
        values.put(COLUMN_TITLE, title)
        values.put(COLUMN_CONTENT, content)
        values.put(COLUMN_TIMESTAMP, getCurrentTimestamp())

        val id = db.insert(TABLE_NOTES, null, values)
        db.close()
        return id
    }

    fun getAllNotes(): List<Note> {
        val notesList = mutableListOf<Note>()
        val db = this.readableDatabase
        val query = "SELECT * FROM $TABLE_NOTES ORDER BY $COLUMN_TIMESTAMP DESC"
        val cursor = db.rawQuery(query, null)

        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID))
                val title = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TITLE))
                val content = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CONTENT))
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
            val content = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CONTENT))
            val timestamp = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP))
            note = Note(id, title, content, timestamp)
        }
        cursor.close()
        db.close()
        return note
    }

    fun updateNote(id: Long, title: String, content: String): Int {
        val db = this.writableDatabase
        val values = ContentValues()
        values.put(COLUMN_TITLE, title)
        values.put(COLUMN_CONTENT, content)
        values.put(COLUMN_TIMESTAMP, getCurrentTimestamp())

        val result = db.update(TABLE_NOTES, values, "$COLUMN_ID=?", arrayOf(id.toString()))
        db.close()
        return result
    }

    fun deleteNote(id: Long): Int {
        val db = this.writableDatabase
        val result = db.delete(TABLE_NOTES, "$COLUMN_ID=?", arrayOf(id.toString()))
        db.close()
        return result
    }

    // Task operations
    fun insertTask(noteId: Long, taskText: String): Long {
        val db = this.writableDatabase
        val values = ContentValues()
        values.put(COLUMN_NOTE_ID, noteId)
        values.put(COLUMN_TASK_TEXT, taskText)
        values.put(COLUMN_IS_CHECKED, 0)

        val id = db.insert(TABLE_TASKS, null, values)
        db.close()
        return id
    }

    fun getTasksForNote(noteId: Long): List<Task> {
        val tasksList = mutableListOf<Task>()
        val db = this.readableDatabase
        val cursor = db.query(
            TABLE_TASKS,
            null,
            "$COLUMN_NOTE_ID=?",
            arrayOf(noteId.toString()),
            null, null, null
        )

        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TASK_ID))
                val taskText = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TASK_TEXT))
                val isChecked = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_IS_CHECKED)) == 1

                tasksList.add(Task(id, noteId, taskText, isChecked))
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return tasksList
    }

    fun updateTaskStatus(taskId: Long, isChecked: Boolean): Int {
        val db = this.writableDatabase
        val values = ContentValues()
        values.put(COLUMN_IS_CHECKED, if (isChecked) 1 else 0)

        val result = db.update(TABLE_TASKS, values, "$COLUMN_TASK_ID=?", arrayOf(taskId.toString()))
        db.close()
        return result
    }

    fun updateTaskText(taskId: Long, taskText: String): Int {
        val db = this.writableDatabase
        val values = ContentValues()
        values.put(COLUMN_TASK_TEXT, taskText)

        val result = db.update(TABLE_TASKS, values, "$COLUMN_TASK_ID=?", arrayOf(taskId.toString()))
        db.close()
        return result
    }

    fun deleteTask(taskId: Long): Int {
        val db = this.writableDatabase
        val result = db.delete(TABLE_TASKS, "$COLUMN_TASK_ID=?", arrayOf(taskId.toString()))
        db.close()
        return result
    }

    private fun getCurrentTimestamp(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return dateFormat.format(Date())
    }
}
