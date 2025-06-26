// NoteActivity.kt
package com.example.notes

import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.notes.repository.NotesRepositoryImpl
import com.example.notes.theme.ThemeManager
import com.example.notes.viewmodel.NoteViewModel
import com.example.notes.viewmodel.NoteViewModelFactory

class NoteActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTE_ID = "extra_note_id"
    }

    private lateinit var noteEditText: NoteEditText
    private lateinit var btnDone: Button
    private lateinit var btnAddCheckBox: ImageButton

    private val viewModel: NoteViewModel by viewModels {
        NoteViewModelFactory(NotesRepositoryImpl(DatabaseHelper(this)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Initialize theme before setting content view
        ThemeManager.initializeTheme(this)
        
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_edit)

        // Initialize UI components
        btnDone = findViewById(R.id.btnDone)
        noteEditText = findViewById(R.id.etNoteContent)
        btnAddCheckBox = findViewById(R.id.btnAddCheckBox)

        // Get note ID from intent
        val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1)
        if (noteId == -1L) {
            finish()
            return
        }

        // Load note content
        viewModel.loadNote(noteId)
        
        // Setup observers
        setupObservers()

        // Set click listeners
        btnDone.setOnClickListener {
            saveNote()
            finish()
        }

        btnAddCheckBox.setOnClickListener {
            noteEditText.appendNewCheckbox()
        }
    }

    override fun onPause() {
        super.onPause()
        // Save note when leaving the activity
        saveNote()
    }
    
    private fun setupObservers() {
        // Observe current note
        viewModel.currentNote.observe(this) { note ->
            note?.let {
                noteEditText.setTitleAndContent(it.title, it.content)
            }
        }
        
        // Observe if it's a new note
        viewModel.isNewNote.observe(this) { isNewNote ->
            if (isNewNote) {
                noteEditText.requestFocus()
            }
        }
    }

    private fun saveNote() {
        val (title, content) = noteEditText.getTitleAndContent()
        viewModel.saveNote(title, content)
    }
}
