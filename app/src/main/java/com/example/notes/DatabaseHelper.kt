// DatabaseHelper.kt
package com.example.notes

import android.content.ContentValues
import android.content.Context
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
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
        return note
    }

    fun updateNote(id: Long, title: String, content: String): Int {
        val db = this.writableDatabase
        val values = ContentValues()
        values.put(COLUMN_TITLE, title)
        values.put(COLUMN_CONTENT, content)
        values.put(COLUMN_TIMESTAMP, getCurrentTimestamp())

        val result = db.update(TABLE_NOTES, values, "$COLUMN_ID=?", arrayOf(id.toString()))
        return result
    }

    fun deleteNotes(noteIds: List<Long>): Int {
        val db = this.writableDatabase
        var deletedCount = 0

        for (id in noteIds) {
            deletedCount += db.delete(TABLE_NOTES, "$COLUMN_ID=?", arrayOf(id.toString()))
        }

        return deletedCount
    }

    private fun getCurrentTimestamp(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return dateFormat.format(Date())
    }
}
