package com.example.notes

data class TaskDraft(
    val id: Long? = null,
    val text: String = "",
    val completed: Boolean = false
)
