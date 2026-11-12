package com.example.notes

import android.os.Bundle
import android.view.KeyEvent
import androidx.compose.runtime.Composable

/** Destination-owned editor; bodies stay in SQLite/private recovery files, never in Binder. */
internal interface EditorSession {
    fun saveForLeave(): Boolean
    fun showError(message: String)
    fun captureState(): Bundle
    fun handleShortcut(event: KeyEvent): Boolean
    fun dispose() {}
    @Composable fun Content(settings: FontSettings)
}
