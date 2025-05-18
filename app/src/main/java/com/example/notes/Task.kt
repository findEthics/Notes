package com.example.notes

data class Task(
    val id: Long,
    val noteId: Long,
    val text: String,
    val isChecked: Boolean
)
