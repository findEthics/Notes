package com.example.notes

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var dbHelper: DatabaseHelper
    private lateinit var noteAdapter: NoteAdapter
    private lateinit var rvNotes: RecyclerView
    private lateinit var fabAddNote: FloatingActionButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize SQLite helper
        dbHelper = DatabaseHelper(this)

        // Initialize UI components
        rvNotes = findViewById(R.id.rvNotes)
        fabAddNote = findViewById(R.id.fabAddNote)

        setupRecyclerView()

        // Set up click listener for FAB to add new note
        fabAddNote.setOnClickListener {
            showCreateNoteDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh the notes list when returning to this activity
        loadNotes()
    }

    private fun setupRecyclerView() {
        // Initialize adapter
        noteAdapter = NoteAdapter(emptyList()) { note ->
            // Handle note click - open the note for editing
            val intent = Intent(this, NoteActivity::class.java).apply {
                putExtra(NoteActivity.EXTRA_NOTE_ID, note.id)
            }
            startActivity(intent)
        }

        // Set up RecyclerView
        rvNotes.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = noteAdapter
        }

        // Load initial notes
        loadNotes()
    }

    private fun loadNotes() {
        val notes = dbHelper.getAllNotes()
        noteAdapter.updateNotes(notes)
    }

    private fun showCreateNoteDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_task, null)
        val etNewTaskText = view.findViewById<EditText>(R.id.etNewTaskText)

        AlertDialog.Builder(this)
            .setTitle("Create New Note")
            .setView(view)
            .setPositiveButton("Create") { _, _ ->
                val title = etNewTaskText.text.toString().trim()
                if (title.isNotEmpty()) {
                    // Create new note
                    val noteId = dbHelper.insertNote(title)

                    // Open the new note
                    val intent = Intent(this, NoteActivity::class.java).apply {
                        putExtra(NoteActivity.EXTRA_NOTE_ID, noteId)
                    }
                    startActivity(intent)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

