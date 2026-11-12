package com.example.notes

data class TaskItem(
    val id: Long,
    val text: String,
    val completed: Boolean,
    val updatedAt: String
)

data class TaskSummary(
    val task: TaskItem
)
