package com.example.notes.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.notes.Note

interface NotesRepository {
    fun getAllNotes(): LiveData<List<Note>>
    suspend fun getNoteById(id: Long): Note?
    suspend fun insertNote(title: String, content: String): Long
    suspend fun updateNote(id: Long, title: String, content: String): Int
    suspend fun deleteNotes(noteIds: List<Long>): Int
    fun refreshNotes()
}