package com.example.notes.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.notes.Note
import com.example.notes.repository.NotesRepository
import kotlinx.coroutines.launch

class MainViewModel(private val repository: NotesRepository) : ViewModel() {
    
    private val _isSelectionMode = MutableLiveData<Boolean>(false)
    val isSelectionMode: LiveData<Boolean> = _isSelectionMode
    
    private val _deleteButtonVisibility = MutableLiveData<Boolean>(false)
    val deleteButtonVisibility: LiveData<Boolean> = _deleteButtonVisibility
    
    val allNotes: LiveData<List<Note>> = repository.getAllNotes()
    
    fun createNewNote(onNoteCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val noteId = repository.insertNote("", "")
            onNoteCreated(noteId)
        }
    }
    
    fun toggleSelectionMode() {
        val newMode = !(_isSelectionMode.value ?: false)
        _isSelectionMode.value = newMode
        _deleteButtonVisibility.value = newMode
    }
    
    fun deleteSelectedNotes(selectedIds: List<Long>) {
        if (selectedIds.isNotEmpty()) {
            viewModelScope.launch {
                repository.deleteNotes(selectedIds)
                // Exit selection mode after deletion
                _isSelectionMode.value = false
                _deleteButtonVisibility.value = false
            }
        }
    }
    
    fun refreshNotes() {
        repository.refreshNotes()
    }
}

class MainViewModelFactory(private val repository: NotesRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}