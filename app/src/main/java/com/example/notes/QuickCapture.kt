package com.example.notes

import android.content.Intent
import android.os.Bundle

internal fun String.takeCapture(maxChars: Int = 10_000): String =
    if (length > maxChars && maxChars > 0 && this[maxChars - 1].isHighSurrogate()) substring(0, maxChars - 1) else take(maxChars)

internal data class QuickCapture(val action: String, val text: String) {
    fun save() = Bundle().apply { putString("action", action); putString("text", text) }

    companion object {
        const val NEW_NOTE = "com.example.notes.action.NEW_NOTE"
        const val NEW_TODO = "com.example.notes.action.NEW_TODO"
        const val APPEND_NOTE = "com.example.notes.action.APPEND_NOTE"
        fun from(intent: Intent): QuickCapture? {
            val action = intent.action?.takeIf { it in setOf(NEW_NOTE, NEW_TODO, APPEND_NOTE) } ?: return null
            // Only a bounded String is accepted; malformed parcelables and wrong types are ignored.
            val text = runCatching { intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeCapture() }.getOrNull().orEmpty()
            return QuickCapture(action, text)
        }
        fun restore(state: Bundle) = QuickCapture(state.getString("action")!!, state.getString("text").orEmpty())
    }
}
