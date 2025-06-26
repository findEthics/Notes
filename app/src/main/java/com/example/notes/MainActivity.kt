package com.example.notes

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.notes.repository.NotesRepositoryImpl
import com.example.notes.theme.ThemeManager
import com.example.notes.viewmodel.MainViewModel
import com.example.notes.viewmodel.MainViewModelFactory

class MainActivity : AppCompatActivity() {

    private lateinit var noteAdapter: NoteAdapter
    private lateinit var ibAddNote: ImageButton
    private lateinit var ibEditNotes: ImageButton
    private lateinit var ibThemeToggle: ImageButton
    private lateinit var rvNotes: RecyclerView
    private lateinit var btnDelete: Button

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(NotesRepositoryImpl(DatabaseHelper(this)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Initialize theme before setting content view
        ThemeManager.initializeTheme(this)
        
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI components
        ibAddNote = findViewById(R.id.ibAddNote)
        ibEditNotes = findViewById(R.id.ibEditNotes)
        ibThemeToggle = findViewById(R.id.ibThemeToggle)
        rvNotes = findViewById(R.id.rvNotes)
        btnDelete = findViewById(R.id.btnDelete)

        // Setup RecyclerView
        setupRecyclerView()
        
        // Setup observers
        setupObservers()
        
        // Update theme toggle button icon
        updateThemeToggleIcon()

        // Set click listeners
        ibAddNote.setOnClickListener {
            viewModel.createNewNote { noteId ->
                openNoteEditor(noteId)
            }
        }

        ibEditNotes.setOnClickListener {
            viewModel.toggleSelectionMode()
        }
        
        ibThemeToggle.setOnClickListener {
            ThemeManager.toggleTheme(this)
            updateThemeToggleIcon()
        }

        btnDelete.setOnClickListener {
            deleteSelectedNotes()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshNotes()
    }

    private fun setupRecyclerView() {
        noteAdapter = NoteAdapter(emptyList()) { note ->
            openNoteEditor(note.id)
        }

        rvNotes.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = noteAdapter
        }
    }
    
    private fun setupObservers() {
        // Observe notes list
        viewModel.allNotes.observe(this) { notes ->
            noteAdapter.updateNotes(notes)
        }
        
        // Observe selection mode
        viewModel.isSelectionMode.observe(this) { isSelectionMode ->
            noteAdapter.toggleSelectionMode(isSelectionMode)
        }
        
        // Observe delete button visibility
        viewModel.deleteButtonVisibility.observe(this) { isVisible ->
            btnDelete.visibility = if (isVisible) View.VISIBLE else View.GONE
        }
    }

    private fun openNoteEditor(noteId: Long) {
        val intent = Intent(this, NoteActivity::class.java).apply {
            putExtra(NoteActivity.EXTRA_NOTE_ID, noteId)
        }
        startActivity(intent)
    }

    private fun deleteSelectedNotes() {
        val selectedIds = noteAdapter.getSelectedNoteIds()
        viewModel.deleteSelectedNotes(selectedIds)
    }
    
    private fun updateThemeToggleIcon() {
        val isDarkMode = ThemeManager.isDarkMode(this)
        val iconRes = if (isDarkMode) R.drawable.ic_light_mode else R.drawable.ic_dark_mode
        ibThemeToggle.setImageResource(iconRes)
    }
}
