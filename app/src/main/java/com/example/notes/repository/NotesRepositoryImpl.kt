package com.example.notes.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.notes.DatabaseHelper
import com.example.notes.Note
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotesRepositoryImpl(private val databaseHelper: DatabaseHelper) : NotesRepository {
    
    private val _allNotes = MutableLiveData<List<Note>>()
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    init {
        refreshNotes()
    }
    
    override fun getAllNotes(): LiveData<List<Note>> = _allNotes
    
    override suspend fun getNoteById(id: Long): Note? = withContext(Dispatchers.IO) {
        databaseHelper.getNoteById(id)
    }
    
    override suspend fun insertNote(title: String, content: String): Long = withContext(Dispatchers.IO) {
        val result = databaseHelper.insertNote(title, content)
        refreshNotes()
        result
    }
    
    override suspend fun updateNote(id: Long, title: String, content: String): Int = withContext(Dispatchers.IO) {
        val result = databaseHelper.updateNote(id, title, content)
        refreshNotes()
        result
    }
    
    override suspend fun deleteNotes(noteIds: List<Long>): Int = withContext(Dispatchers.IO) {
        val result = databaseHelper.deleteNotes(noteIds)
        refreshNotes()
        result
    }
    
    override fun refreshNotes() {
        repositoryScope.launch {
            try {
                val notes = databaseHelper.getAllNotes()
                _allNotes.postValue(notes)
            } catch (e: Exception) {
                // Handle database errors gracefully
                e.printStackTrace()
                _allNotes.postValue(emptyList())
            }
        }
    }
}