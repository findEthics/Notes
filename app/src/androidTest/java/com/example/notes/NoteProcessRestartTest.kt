package com.example.notes

import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/** Run the two methods separately, with automation-only am force-stop between runners. */
@RunWith(AndroidJUnit4::class)
class NoteProcessRestartTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val receipt = java.io.File(context.filesDir, "note-process-restart.json")
    private val title = "process-${java.util.UUID.randomUUID()}"
    private val body = "Committed preview\n" + "x".repeat(50_000)
    private fun open(id: Long) = launchNote(id)

    @Test fun prepareCommittedReplacementAndStateCapture() {
        DatabaseHelper(context).use { helper ->
            val id = helper.insertNote(title, "Before")
            open(id).use { scenario ->
                compose.onNodeWithTag("note-text").performTextReplacement("$title\n$body")
                compose.onNodeWithTag("note-text").performSemanticsAction(SemanticsActions.SetSelection) { it(2, 4, false) }
                compose.waitForIdle()
                helper.deleteNotes(listOf(id))
                val state = Bundle()
                scenario.onActivity {
                    instrumentation.callActivityOnSaveInstanceState(it, state)
                    state.putAll(it.currentEditor!!.captureState())
                }
                val replacement = state.getLong(NoteScreen.EXTRA_NOTE_ID)
                assertNotEquals(id, replacement)
                assertEquals(2, state.getInt("selection_start")); assertEquals(4, state.getInt("selection_end"))
                assertFalse(state.containsKey("unsaved_note"))
                receipt.writeText(JSONObject().put("id", replacement).put("pid", Process.myPid()).put("title", title).toString())
            }
        }
    }
    @Test fun verifyCommittedFullBodyInFreshProcess() {
        val saved = JSONObject(receipt.readText())
        val id = saved.getLong("id")
        assertNotEquals(saved.getInt("pid"), Process.myPid())
        DatabaseHelper(context).use { helper ->
            try {
                assertEquals(body, helper.getNoteById(id)!!.content)
                assertEquals("Committed preview", helper.getNotePreviews().single { it.id == id }.content)
                open(id).use {
                    compose.onNodeWithTag("note-text").assertTextEquals("${saved.getString("title")}\n$body")
                    compose.onNodeWithTag("note-text").assertIsFocused()
                compose.onNodeWithTag("done").performClick()
                }
                assertEquals(1, helper.getNotePreviews().count { it.title == saved.getString("title") })
                assertEquals(body, helper.getNoteById(id)!!.content)
            } finally { helper.deleteNotes(listOf(id)); receipt.delete() }
        }
    }
}
