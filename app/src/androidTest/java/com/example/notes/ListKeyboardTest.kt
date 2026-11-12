package com.example.notes

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Fixture-only emulator. Deletes only IDs created by this class. */
@RunWith(AndroidJUnit4::class)
class ListKeyboardTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val helper = DatabaseHelper(context)
    private val notes = mutableListOf<Long>()
    private val tasks = mutableListOf<Long>()
    @After fun cleanup() { helper.deleteNotes(notes); tasks.forEach { helper.deleteTask(it) }; helper.close() }
    private fun note(title: String = "Keyboard note") = helper.insertNote(title, "Preview").also { notes.add(it) }
    private fun ready(tag: String) = compose.waitUntil(10000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodesWithTag("loading").fetchSemanticsNodes().isEmpty()
    }
    private fun key(code: Int, meta: Int = 0) {
        compose.waitForIdle()
        val now = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
        instrumentation.waitForIdleSync()
    }
    private fun focus(tag: String) {
        ready(tag)
        compose.onNodeWithTag("items").performScrollToNode(hasTestTag(tag))
        // Actual key input enters keyboard mode before requesting the row's semantics focus.
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        compose.onNodeWithTag(tag).assertIsFocused()
    }
    private fun shot(name: String) {
        compose.waitForIdle()
        val mode = if (context.resources.configuration.uiMode and 0x30 == 0x20) "dark" else "light"
        val directory = File(context.getExternalFilesDir(null), "phase-d").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            File(directory, "$mode-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun shiftAndAltSpaceSwitchAndEscapeCancelsSelection() {
        val id = note()
        ActivityScenario.launch(MainActivity::class.java).use {
            ready("items")
            for (meta in listOf(KeyEvent.META_SHIFT_ON, KeyEvent.META_ALT_ON)) {
                repeat(3) {
                    key(KeyEvent.KEYCODE_SPACE, meta)
                    compose.onNodeWithTag("section-TASKS").assertIsSelected()
                    key(KeyEvent.KEYCODE_SPACE, meta)
                    compose.onNodeWithTag("section-NOTES").assertIsSelected()
                }
            }
            compose.onNodeWithTag("items").performScrollToKey("note-$id")
            compose.onNodeWithTag("note-$id").performTouchInput { longClick() }
            compose.onNodeWithTag("note-$id").assertIsSelected()
            key(KeyEvent.KEYCODE_ESCAPE)
            compose.onNodeWithTag("delete-selected").assertDoesNotExist()
        }
    }
    @Test fun deleteUndoKeepsSameIdAndMovesFocusToNeighbour() {
        val first = note("First"); val second = note("Second")
        ActivityScenario.launch(MainActivity::class.java).use {
            focus("note-$second"); shot("focused-row")
            key(KeyEvent.KEYCODE_DEL)
            compose.onNodeWithText("Deleted 1").assertIsDisplayed()
            compose.onNodeWithTag("note-$second").assertDoesNotExist()
            compose.onNodeWithTag("note-$first").assertIsFocused()
            assertNotNull(helper.getNoteById(second)); shot("snackbar")
            key(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
            compose.onNodeWithTag("note-$second").assertExists().assertIsFocused()
            assertEquals(second, helper.getNoteById(second)!!.id)
        }
    }
    @Test fun forwardDeleteDismissCommits() {
        val id = note()
        ActivityScenario.launch(MainActivity::class.java).use {
            focus("note-$id"); key(KeyEvent.KEYCODE_FORWARD_DEL)
            compose.onNodeWithContentDescription("Dismiss").performClick()
            compose.waitUntil(10000) { helper.getNoteById(id) == null }
            compose.onNodeWithTag("note-$id").assertDoesNotExist()
        }
    }
    @Test fun deleteTimeoutCommits() {
        val id = note()
        ActivityScenario.launch(MainActivity::class.java).use {
            focus("note-$id"); key(KeyEvent.KEYCODE_DEL)
            compose.waitUntil(15000) { helper.getNoteById(id) == null }
            compose.onNodeWithTag("note-$id").assertDoesNotExist()
        }
    }
    @Test fun pendingDeleteCommitsOnStop() {
        val id = note()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            focus("note-$id"); key(KeyEvent.KEYCODE_DEL)
            assertNotNull(helper.getNoteById(id))
            scenario.moveToState(Lifecycle.State.CREATED)
            assertNull(helper.getNoteById(id))
            scenario.moveToState(Lifecycle.State.RESUMED)
            ready("items"); compose.onNodeWithTag("note-$id").assertDoesNotExist()
        }
    }
    @Test fun pendingDeleteSurvivesRecreationAndUndoThenCommitsOnRealStop() {
        val id = note("Rotate pending delete")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            focus("note-$id"); key(KeyEvent.KEYCODE_DEL)
            scenario.recreate()
            ready("items")
            compose.onNodeWithText("Deleted 1").assertIsDisplayed()
            compose.onNodeWithTag("note-$id").assertDoesNotExist()
            assertNotNull(helper.getNoteById(id))
            compose.onNodeWithText("Undo").performClick()
            ready("note-$id")
            assertEquals(id, helper.getNoteById(id)!!.id)
            focus("note-$id"); key(KeyEvent.KEYCODE_DEL)
            scenario.recreate(); ready("items")
            compose.onNodeWithText("Undo").assertIsDisplayed()
            scenario.moveToState(Lifecycle.State.CREATED)
            assertNull(helper.getNoteById(id))
            scenario.moveToState(Lifecycle.State.RESUMED); ready("items")
            compose.onNodeWithText("Undo").assertDoesNotExist()
            compose.onNodeWithTag("note-$id").assertDoesNotExist()
        }
    }
    @Test fun multiselectUndoRestoresBothIds() {
        val ids = listOf(note("One"), note("Two"))
        ActivityScenario.launch(MainActivity::class.java).use {
            ready("items")
            compose.onNodeWithTag("items").performScrollToKey("note-${ids.first()}")
            compose.onNodeWithTag("note-${ids.first()}").performTouchInput { longClick() }
            ids.drop(1).forEach {
                compose.onNodeWithTag("items").performScrollToKey("note-$it")
                compose.onNodeWithTag("note-$it").performClick()
            }
            compose.onNodeWithTag("delete-selected").performClick()
            compose.onNodeWithText("Deleted 2").assertIsDisplayed()
            compose.onNodeWithText("Undo").performClick()
            ids.forEach { compose.onNodeWithTag("note-$it").assertExists(); assertEquals(it, helper.getNoteById(it)!!.id) }
        }
    }
    @Test fun controlDTogglesTaskAndDeleteUndoKeepsId() {
        val id = helper.insertTask("Keyboard to-do")!!; tasks.add(id)
        ActivityScenario.launch(MainActivity::class.java).use {
            ready("items"); key(KeyEvent.KEYCODE_SPACE, KeyEvent.META_SHIFT_ON)
            focus("task-$id"); key(KeyEvent.KEYCODE_D, KeyEvent.META_CTRL_ON)
            compose.waitUntil(10000) { helper.getTaskById(id)?.completed == true }
            ready("task-$id"); compose.onNodeWithTag("task-completed-$id").assertIsOn(); shot("todos-completed")
            focus("task-$id"); key(KeyEvent.KEYCODE_DEL)
            compose.onNodeWithText("Undo").performClick()
            compose.onNodeWithTag("task-$id").assertExists()
            assertEquals(id, helper.getTaskById(id)!!.id)
        }
    }
    @Test fun returningFromEditorRestoresOpenedRowFocus() {
        val id = note()
        ActivityScenario.launch(MainActivity::class.java).use {
            focus("note-$id"); key(KeyEvent.KEYCODE_ENTER)
            ready("note-text"); key(KeyEvent.KEYCODE_ESCAPE)
            ready("note-$id"); compose.onNodeWithTag("note-$id").assertIsFocused()
        }
    }
    @Test fun longPressTaskSupportsSnackbarAndKeyboardUndoThenTimeoutCommit() {
        val id = helper.insertTask("Long press to-do")!!; tasks.add(id)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("section-TASKS").performClick()
            for (keyboardUndo in listOf(false, true)) {
                ready("task-$id")
                compose.onNodeWithTag("items").performScrollToKey("task-$id")
                compose.onNodeWithTag("task-$id").performTouchInput { longClick() }
                compose.onNodeWithText("Deleted 1").assertIsDisplayed()
                compose.onNodeWithTag("task-$id").assertDoesNotExist()
                assertEquals(id, helper.getTaskById(id)!!.id)
                if (keyboardUndo) key(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
                else compose.onNodeWithText("Undo").performClick()
                ready("task-$id")
                assertEquals(id, helper.getTaskById(id)!!.id)
            }
            compose.onNodeWithTag("task-$id").performTouchInput { longClick() }
            compose.onNodeWithText("Deleted 1").assertIsDisplayed()
            compose.waitUntil(15000) { helper.getTaskById(id) == null }
            compose.onNodeWithTag("task-$id").assertDoesNotExist()
        }
    }
    @Test fun deleteCheckedSupportsBothUndoPathsAndOnlyCommitsCheckedTasks() {
        val checked = listOf(helper.insertTask("Checked one", true)!!, helper.insertTask("Checked two", true)!!)
        val unchecked = helper.insertTask("Keep unchecked")!!
        tasks.addAll(checked + unchecked)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("section-TASKS").performClick()
            for (keyboardUndo in listOf(false, true)) {
                ready("delete-checked")
                compose.onNodeWithTag("delete-checked").performClick()
                compose.onNodeWithText("Deleted 2").assertIsDisplayed()
                compose.onNodeWithTag("delete-checked").assertDoesNotExist()
                checked.forEach { id ->
                    compose.onNodeWithTag("task-$id").assertDoesNotExist()
                    assertTrue(helper.getTaskById(id)!!.completed)
                }
                compose.onNodeWithTag("task-$unchecked").assertIsDisplayed()
                assertFalse(helper.getTaskById(unchecked)!!.completed)
                if (keyboardUndo) key(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
                else compose.onNodeWithText("Undo").performClick()
                ready("delete-checked")
                checked.forEach { id ->
                    compose.onNodeWithTag("task-$id").assertExists()
                    assertEquals(id, helper.getTaskById(id)!!.id)
                    assertTrue(helper.getTaskById(id)!!.completed)
                }
            }
            compose.onNodeWithTag("delete-checked").performClick()
            compose.onNodeWithText("Deleted 2").assertIsDisplayed()
            compose.waitUntil(15000) { checked.all { helper.getTaskById(it) == null } }
            assertFalse(helper.getTaskById(unchecked)!!.completed)
            compose.onNodeWithTag("task-$unchecked").assertIsDisplayed()
        }
    }
    @Test fun creationShortcutsDoNotReplaceEditorWhileTyping() {
        val id = note()
        launchNote(id).use { scenario ->
            ready("note-text"); compose.onNodeWithTag("note-text").performClick()
            compose.onNodeWithTag("note-text").performTextReplacement("Shift")
            key(KeyEvent.KEYCODE_MOVE_END)
            key(KeyEvent.KEYCODE_SPACE, KeyEvent.META_SHIFT_ON)
            compose.onNodeWithTag("note-text").assertTextEquals("Shift ")
            for (code in listOf(KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_T))
                for (meta in listOf(KeyEvent.META_ALT_ON, KeyEvent.META_SHIFT_ON)) {
                    key(code, meta)
                    scenario.onActivity { assertEquals("note/{id}", it.navController.currentDestination?.route) }
                    compose.onNodeWithTag("note-text").assertIsFocused()
                }
        }
    }
    @Test fun emptyStateScreenshots() {
        assertTrue("Isolated emulator must have no remaining notes", helper.getNotePreviews().isEmpty())
        ActivityScenario.launch(MainActivity::class.java).use {
            ready("items"); compose.onNodeWithText("No notes yet · Alt+N or + to create").assertIsDisplayed(); shot("notes-empty")
            compose.onNodeWithTag("section-TASKS").performClick()
            compose.onNodeWithText("No to-dos yet · Alt+T or + to create").assertIsDisplayed(); shot("todos-empty")
        }
    }
}
