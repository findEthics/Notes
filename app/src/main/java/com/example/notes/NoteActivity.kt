package com.example.notes

import android.content.DialogInterface
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class NoteActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTE_ID = "extra_note_id"
    }

    private lateinit var dbHelper: DatabaseHelper
    private lateinit var taskAdapter: TaskAdapter

    private lateinit var etTitle: EditText
    private lateinit var etContent: EditText
    private lateinit var btnAddTask: Button
    private lateinit var rvTasks: RecyclerView
    private lateinit var btnSaveNote: Button

    private var noteId: Long = -1
    private lateinit var note: Note

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note)

        // Initialize SQLite helper
        dbHelper = DatabaseHelper(this)

        // Initialize UI components
        etTitle = findViewById(R.id.etTitle)
        etContent = findViewById(R.id.etContent)
        btnAddTask = findViewById(R.id.btnAddTask)
        rvTasks = findViewById(R.id.rvTasks)
        btnSaveNote = findViewById(R.id.btnSaveNote)

        // Get note ID from intent
        noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1)
        if (noteId == -1L) {
            Toast.makeText(this, "Error opening note", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Load note details
        loadNote()

        // Set up tasks RecyclerView
        setupTasksRecyclerView()

        // Set click listeners
        btnAddTask.setOnClickListener {
            showAddTaskDialog()
        }

        btnSaveNote.setOnClickListener {
            saveNote()
        }
    }

    private fun loadNote() {
        val loadedNote = dbHelper.getNoteById(noteId)
        if (loadedNote != null) {
            note = loadedNote
            etTitle.setText(note.title)
            etContent.setText(note.content)
        } else {
            Toast.makeText(this, "Note not found", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun setupTasksRecyclerView() {
        // Initialize adapter with callbacks
        taskAdapter = TaskAdapter(
            tasks = dbHelper.getTasksForNote(noteId).toMutableList(),
            onTaskCheckedChanged = { task, isChecked ->
                dbHelper.updateTaskStatus(task.id, isChecked)
            },
            onTaskTextChanged = { task, newText ->
                dbHelper.updateTaskText(task.id, newText)
            },
            onTaskDeleted = { task ->
                dbHelper.deleteTask(task.id)
                Toast.makeText(this, "Task deleted", Toast.LENGTH_SHORT).show()
            }
        )

        // Set up RecyclerView
        rvTasks.apply {
            layoutManager = LinearLayoutManager(this@NoteActivity)
            adapter = taskAdapter
        }
    }

    private fun showAddTaskDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_task, null)
        val etNewTaskText = view.findViewById<EditText>(R.id.etNewTaskText)

        AlertDialog.Builder(this)
            .setTitle("Add Task")
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                val taskText = etNewTaskText.text.toString().trim()
                if (taskText.isNotEmpty()) {
                    val taskId = dbHelper.insertTask(noteId, taskText)
                    val newTask = Task(taskId, noteId, taskText, false)
                    taskAdapter.addTask(newTask)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveNote() {
        val title = etTitle.text.toString().trim()
        val content = etContent.text.toString().trim()

        if (title.isEmpty()) {
            Toast.makeText(this, "Title cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        dbHelper.updateNote(noteId, title, content)
        Toast.makeText(this, "Note saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
