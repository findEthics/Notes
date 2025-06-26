package com.example.notes.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.notes.Note
import com.example.notes.repository.NotesRepository
import kotlinx.coroutines.launch

class NoteViewModel(private val repository: NotesRepository) : ViewModel() {
    
    private val _currentNote = MutableLiveData<Note?>()
    val currentNote: LiveData<Note?> = _currentNote
    
    private val _isNewNote = MutableLiveData<Boolean>(false)
    val isNewNote: LiveData<Boolean> = _isNewNote
    
    private var noteId: Long = -1
    
    fun loadNote(id: Long) {
        noteId = id
        viewModelScope.launch {
            val note = repository.getNoteById(id)
            _currentNote.value = note
            _isNewNote.value = note?.title.isNullOrEmpty() && note?.content.isNullOrEmpty()
        }
    }
    
    fun saveNote(title: String, content: String) {
        if (noteId == -1L) return
        
        viewModelScope.launch {
            if (title.isNotEmpty() || content.isNotEmpty()) {
                repository.updateNote(noteId, title, content)
            } else {
                // Delete empty notes
                repository.deleteNotes(listOf(noteId))
            }
        }
    }
    
    fun getCurrentNoteId(): Long = noteId
}

class NoteViewModelFactory(private val repository: NotesRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NoteViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return NoteViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}