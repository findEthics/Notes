// NoteActivity.kt
package com.example.notes

import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.notes.DatabaseHelper
import com.example.notes.NoteEditText
import com.example.notes.R
import java.util.*

class NoteActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTE_ID = "extra_note_id"
    }

    private lateinit var dbHelper: DatabaseHelper
    private lateinit var noteEditText: NoteEditText
    private var noteId: Long = -1

    private lateinit var btnDone: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_edit)

        // Initialize SQLite helper
        dbHelper = DatabaseHelper(this)

        // Initialize UI components
        btnDone = findViewById(R.id.btnDone)
        noteEditText = findViewById(R.id.etNoteContent)

        // Get note ID from intent
        noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1)
        if (noteId == -1L) {
            finish()
            return
        }

        // Load note content
        loadNote()

        // Set click listeners
        btnDone.setOnClickListener {
            saveNote()
            finish()
        }

    }

    override fun onPause() {
        super.onPause()
        // Save note when leaving the activity
        saveNote()
    }

    private fun loadNote() {
        val note = dbHelper.getNoteById(noteId)
        if (note != null) {
            noteEditText.setTitleAndContent(note.title, note.content)
        }

        // Set focus to the beginning if it's a new note
        if (note?.title.isNullOrEmpty() && note?.content.isNullOrEmpty()) {
            noteEditText.requestFocus()
        }
    }

    private fun saveNote() {
        val (title, content) = noteEditText.getTitleAndContent()

        // Only save if there's actual content
        if (title.isNotEmpty() || content.isNotEmpty()) {
            dbHelper.updateNote(noteId, title, content)
        } else {
            // Delete empty notes
            dbHelper.deleteNotes(listOf(noteId))
        }
    }
}
