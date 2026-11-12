package com.example.notes

import android.content.Intent
import android.os.Bundle

internal data class QuickCapture(val action: String, val text: String) {
    fun save() = Bundle().apply { putString("action", action); putString("text", text) }

    companion object {
        const val NEW_NOTE = "com.example.notes.action.NEW_NOTE"
        const val NEW_TODO = "com.example.notes.action.NEW_TODO"
        const val APPEND_NOTE = "com.example.notes.action.APPEND_NOTE"
        fun from(intent: Intent): QuickCapture? {
            val action = intent.action?.takeIf { it in setOf(NEW_NOTE, NEW_TODO, APPEND_NOTE) } ?: return null
            // Only a bounded String is accepted; malformed parcelables and wrong types are ignored.
            val text = runCatching { intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.take(10_000) }.getOrNull().orEmpty()
            return QuickCapture(action, text)
        }
        fun restore(state: Bundle) = QuickCapture(state.getString("action")!!, state.getString("text").orEmpty())
    }
}
