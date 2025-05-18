package com.example.notes

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var dbHelper: DatabaseHelper
    private lateinit var noteAdapter: NoteAdapter

    private lateinit var ibAddNote: ImageButton
    private lateinit var ibEditNotes: ImageButton
    private lateinit var rvNotes: RecyclerView
    private lateinit var btnDelete: Button

    private var isInSelectionMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize SQLite helper
        dbHelper = DatabaseHelper(this)

        // Initialize UI components
        ibAddNote = findViewById(R.id.ibAddNote)
        ibEditNotes = findViewById(R.id.ibEditNotes)
        rvNotes = findViewById(R.id.rvNotes)
        btnDelete = findViewById(R.id.btnDelete)

        // Setup RecyclerView
        setupRecyclerView()

        // Set click listeners
        ibAddNote.setOnClickListener {
            // Create a new empty note
            val noteId = dbHelper.insertNote("", "")
            openNoteEditor(noteId)
        }

        ibEditNotes.setOnClickListener {
            toggleSelectionMode()
        }

        btnDelete.setOnClickListener {
            deleteSelectedNotes()
        }
 // Update every minute
    }

    override fun onResume() {
        super.onResume()
        loadNotes()
    }


    private fun setupRecyclerView() {
        noteAdapter = NoteAdapter(emptyList()) { note ->
            openNoteEditor(note.id)
        }

        rvNotes.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = noteAdapter
//            addItemDecoration(DividerItemDecoration(this@MainActivity, DividerItemDecoration.VERTICAL))
        }

        loadNotes()
    }

    private fun loadNotes() {
        val notes = dbHelper.getAllNotes()
        noteAdapter.updateNotes(notes)
    }

    private fun openNoteEditor(noteId: Long) {
        val intent = Intent(this, NoteActivity::class.java).apply {
            putExtra(NoteActivity.EXTRA_NOTE_ID, noteId)
        }
        startActivity(intent)
    }

    private fun toggleSelectionMode() {
        isInSelectionMode = !isInSelectionMode
        noteAdapter.toggleSelectionMode(isInSelectionMode)
        btnDelete.visibility = if (isInSelectionMode) View.VISIBLE else View.GONE
    }

    private fun deleteSelectedNotes() {
        val selectedIds = noteAdapter.getSelectedNoteIds()
        if (selectedIds.isNotEmpty()) {
            dbHelper.deleteNotes(selectedIds)
            loadNotes()
            toggleSelectionMode() // Exit selection mode
        }
    }
}
