package com.example.notes

import android.os.Bundle
import android.os.Parcel
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.foundation.text.input.TextFieldState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Before-fix Binder rejection evidence is retained with the remediation report. */
@RunWith(AndroidJUnit4::class)
class SavedStateSizeProbeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun bytes(state: Bundle): Int = Parcel.obtain().let { parcel ->
        try { parcel.writeBundle(state); parcel.dataSize() } finally { parcel.recycle() }
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> state(activity: Any): androidx.compose.runtime.MutableState<T> =
        (activity as MainActivity).currentEditor!!.let { screen ->
            screen.javaClass.getDeclaredField("draft\$delegate").apply { isAccessible=true }.get(screen) as androidx.compose.runtime.MutableState<T>
        }
    private fun textState(activity: MainActivity, field: String): TextFieldState =
        activity.currentEditor!!.let { screen ->
            screen.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(screen) as TextFieldState
        }

    @Test fun largeCommittedTaskStoresIdsAndUiStateOnlyAndReopensFullText() {
        val ids = mutableListOf<Long>()
        try { launchTask().use { scenario ->
            for(length in listOf(16_000,128_000,600_000)) {
                val saved=Bundle()
                scenario.onActivity { activity ->
                    val mutable=state<TaskDraft>(activity); val before=mutable.value
                    val text = textState(activity, "textState"); val beforeText = text.text.toString()
                    mutable.value=TaskDraft(text="p".repeat(length), completed=true)
                    text.edit { replace(0, this.length, "p".repeat(length)) }
                    instrumentation.callActivityOnSaveInstanceState(activity, saved)
                    mutable.value=before
                    text.edit { replace(0, this.length, beforeText) }
                }
                val editor = checkNotNull(saved.getBundle("inline_task"))
                val id=editor.getLong(TaskScreen.EXTRA_TASK_ID); ids.add(id)
                assertTrue(id>0); assertFalse(editor.containsKey("task_draft")); assertFalse(editor.containsKey("task_recovery"))
                assertTrue(bytes(saved)<16_384)
                DatabaseHelper(context).use { helper ->
                    assertEquals("p".repeat(length),helper.getTaskById(id)!!.text)
                    assertTrue(helper.getTaskById(id)!!.completed)
                }
                android.util.Log.i("SavedStateProbe","committedTask charsEach=$length bytes=${bytes(saved)}")
            }
        } } finally { DatabaseHelper(context).use { helper -> ids.forEach { helper.deleteTask(it) } } }
    }

    @Test fun largeFailedTaskUsesSmallLosslessRecoveryTokenAndCleanup() {
        val saved=Bundle(); val payload="漢😀".repeat(200_000)
        val recovery=DraftRecovery(context,"task"); var token: String? = null
        DatabaseHelper(context).use { it.writableDatabase.execSQL("CREATE TRIGGER recovery_task_failure BEFORE INSERT ON tasks BEGIN SELECT RAISE(ABORT,'probe'); END") }
        try {
            launchTask().use { scenario ->
                scenario.onActivity { activity ->
                    val mutable=state<TaskDraft>(activity); val before=mutable.value
                    val text = textState(activity, "textState"); val beforeText = text.text.toString()
                    mutable.value=TaskDraft(text=payload, completed=true)
                    text.edit { replace(0, length, payload) }
                    instrumentation.callActivityOnSaveInstanceState(activity, saved)
                    mutable.value=before
                    text.edit { replace(0, length, beforeText) }
                }
                val editor = checkNotNull(saved.getBundle("inline_task"))
                assertEquals(-1L, editor.getLong(TaskScreen.EXTRA_TASK_ID))
                assertTrue(bytes(saved)<16_384); assertFalse(editor.containsKey("task_draft"))
                token=checkNotNull(editor.getString("task_recovery"))
                val encoded=recovery.claim(token!!).second
                val json=JSONObject(encoded)
                assertEquals(payload,json.getString("text"))
                assertTrue(json.getBoolean("completed"))
                assertEquals(encoded,recovery.claim(token!!).second) // Interrupted restore remains durable.
                android.util.Log.i("SavedStateProbe","failedTask charsEach=${payload.length} bytes=${bytes(saved)}")
            } // Explicitly returning to the empty new draft discards its recovery on close.
            assertThrows(java.io.FileNotFoundException::class.java) { recovery.claim(token!!) }
        } finally {
            recovery.remove(token)
            DatabaseHelper(context).use { it.writableDatabase.execSQL("DROP TRIGGER IF EXISTS recovery_task_failure") }
        }
    }

    @Test fun largeFailedNoteUsesSmallLosslessRecoveryToken() {
        val id=DatabaseHelper(context).use { it.insertNote("probe","committed") }
        val saved=Bundle(); val payload="probe\n"+"漢😀".repeat(200_000)
        DatabaseHelper(context).use { it.writableDatabase.execSQL("CREATE TRIGGER recovery_note_failure BEFORE UPDATE ON notes WHEN OLD.id=$id BEGIN SELECT RAISE(ABORT,'probe'); END") }
        try {
            launchNote(id).use { scenario ->
                scenario.onActivity { activity ->
                    val text = textState(activity, "draft"); val before = text.text.toString()
                    text.edit { replace(0, length, payload) }
                    instrumentation.callActivityOnSaveInstanceState(activity, saved)
                    saved.putAll(activity.currentEditor!!.captureState())
                    text.edit { replace(0, length, before) }
                }
            }
            assertTrue(bytes(saved)<16_384); assertFalse(saved.containsKey("unsaved_note"))
            val recovery=DraftRecovery(context,"note"); val token=checkNotNull(saved.getString("note_recovery"))
            val(next,text)=recovery.claim(token)
            try { assertEquals(payload,text); assertEquals(payload,recovery.claim(token).second) }
            finally { recovery.remove(next) }
            assertThrows(java.io.FileNotFoundException::class.java) { recovery.claim(token) }
            android.util.Log.i("SavedStateProbe","failedNote chars=${payload.length} bytes=${bytes(saved)}")
        } finally { DatabaseHelper(context).use { it.writableDatabase.execSQL("DROP TRIGGER IF EXISTS recovery_note_failure"); it.deleteNotes(listOf(id)) } }
    }
}
