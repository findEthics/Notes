package com.example.notes

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable emulator only; preserve every row present before each test. */
@RunWith(AndroidJUnit4::class)
class QuickCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val helper = DatabaseHelper(context)
    private var originalNotes = emptySet<Long>()
    private var originalTasks = emptySet<Long>()
    @Before fun rememberRows() {
        originalNotes = helper.getNotePreviews().map { it.id }.toSet()
        originalTasks = helper.getTaskSummaries().map { it.task.id }.toSet()
    }
    @After fun cleanup() {
        helper.deleteNotes(helper.getNotePreviews().map { it.id }.filterNot { it in originalNotes })
        helper.getTaskSummaries().filterNot { it.task.id in originalTasks }.forEach { helper.deleteTask(it.task.id) }
        helper.close()
    }
    private fun intent(action: String, text: String? = null) = Intent(action).setPackage(context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { text?.let { putExtra(Intent.EXTRA_TEXT, it) } }
    private fun launch(action: String, text: String? = null): ActivityScenario<MainActivity> {
        val capture = intent(action, text)
        assertNotNull(capture.resolveActivity(context.packageManager))
        return ActivityScenario.launch(capture)
    }
    private fun ready(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
    }
    private fun caret(tag: String, length: Int) = assertEquals(TextRange(length),
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    private fun blank(tag: String) = compose.onNodeWithTag(tag)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
    private fun key(code: Int, meta: Int = 0) {
        compose.waitForIdle()
        val now = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
        instrumentation.waitForIdleSync()
    }

    @Test fun newNotePrefillAndCaretSurviveRecreationWithoutReplay() {
        launch(QuickCapture.NEW_NOTE, "  Capture title\nBody  ").use { scenario ->
            ready("note-text")
            compose.onNodeWithTag("note-text").assertTextEquals("Capture title\nBody").assertIsFocused()
            caret("note-text", "Capture title\nBody".length)
            scenario.recreate()
            ready("note-text")
            compose.onNodeWithTag("note-text").assertTextEquals("Capture title\nBody")
            compose.onNodeWithTag("done").performClick()
            ready("items")
            assertEquals(1, helper.getNotePreviews().count { it.id !in originalNotes })
            scenario.recreate()
            ready("items")
            compose.onNodeWithTag("note-text").assertDoesNotExist()
        }
    }

    @Test fun blankNewNoteAndTodoFocusTheEditor() {
        launch(QuickCapture.NEW_NOTE).use {
            ready("note-text"); blank("note-text").assertIsFocused()
            compose.onNodeWithTag("done").performClick(); ready("items")
            assertEquals(originalNotes, helper.getNotePreviews().map { it.id }.toSet())
        }
        launch(QuickCapture.NEW_TODO).use {
            ready("task-text"); blank("task-text").assertIsFocused()
        }
    }

    @Test fun newTodoPrefillSurvivesRecreationWithoutReplay() {
        launch(QuickCapture.NEW_TODO, "  Capture task  ").use { scenario ->
            ready("task-text")
            compose.onNodeWithTag("task-text").assertTextEquals("Capture task")
            caret("task-text", "Capture task".length)
            scenario.recreate(); ready("task-text")
            compose.onNodeWithTag("task-text").assertTextEquals("Capture task")
            compose.onNodeWithTag("done").performClick(); ready("items")
            assertEquals(1, helper.getTaskSummaries().count { it.task.id !in originalTasks })
        }
    }

    @Test fun appendPrefillChoosesNoteAndStartsAtEndOfNewLineOnlyOnce() {
        val id = helper.insertNote("Append target", "Existing body")
        launch(QuickCapture.APPEND_NOTE, "  Added text  ").use { scenario ->
            ready("pick-note-$id")
            compose.onNodeWithTag("pick-note-$id").performClick()
            ready("note-text")
            val expected = "Append target\nExisting body\nAdded text"
            compose.onNodeWithTag("note-text").assertTextEquals(expected).assertIsFocused()
            caret("note-text", expected.length)
            scenario.recreate(); ready("note-text")
            compose.onNodeWithTag("note-text").assertTextEquals(expected)
            caret("note-text", expected.length)
            compose.onNodeWithTag("done").performClick(); ready("items")
            assertEquals("Existing body\nAdded text", helper.getNoteById(id)!!.content)
        }
    }

    @Test fun pickerNewNoteKeepsCaptureTextAcrossRecreation() {
        launch(QuickCapture.APPEND_NOTE, "Picker capture\nNew body").use { scenario ->
            ready("note-picker")
            key(KeyEvent.KEYCODE_N, KeyEvent.META_ALT_ON)
            ready("note-text")
            compose.onNodeWithTag("note-text").assertTextEquals("Picker capture\nNew body")
            scenario.recreate(); ready("note-text")
            compose.onNodeWithTag("note-text").assertTextEquals("Picker capture\nNew body")
            compose.onNodeWithTag("done").performClick(); ready("items")
            assertEquals(1, helper.getNotePreviews().count { it.title == "Picker capture" && it.content == "New body" })
        }
    }

    @Test fun failedAppendLoadRetainsCaptureAcrossRecreationUntilRetryAndAppliesOnce() {
        val id = helper.insertNote("Load retry", "Original body")
        var renamed = false
        try {
            launch(QuickCapture.APPEND_NOTE, "Retained capture").use { scenario ->
                ready("pick-note-$id")
                helper.writableDatabase.execSQL("ALTER TABLE notes RENAME TO capture_load_fixture")
                renamed = true
                compose.onNodeWithTag("pick-note-$id").performClick()
                ready("error")
                compose.onNodeWithTag("note-text").assertDoesNotExist()
                scenario.recreate(); ready("error")
                helper.writableDatabase.execSQL("ALTER TABLE capture_load_fixture RENAME TO notes")
                renamed = false
                compose.onNodeWithText(context.getString(R.string.retry)).performClick()
                ready("note-text")
                val expected = "Load retry\nOriginal body\nRetained capture"
                compose.onNodeWithTag("note-text").assertTextEquals(expected)
                caret("note-text", expected.length)
                scenario.recreate(); ready("note-text")
                compose.onNodeWithTag("note-text").assertTextEquals(expected)
                compose.onNodeWithTag("done").performClick(); ready("items")
                assertEquals("Original body\nRetained capture", helper.getNoteById(id)!!.content)
            }
        } finally {
            if (renamed) helper.writableDatabase.execSQL("ALTER TABLE capture_load_fixture RENAME TO notes")
        }
    }

    @Test fun unchangedAppendLeavesStoredNoteAndTimestampUntouchedEvenAfterRecreation() {
        val id = helper.insertNote("Unchanged append", "Body")
        val before = helper.getNoteById(id)
        launch(QuickCapture.APPEND_NOTE).use { scenario ->
            ready("pick-note-$id"); compose.onNodeWithTag("pick-note-$id").performClick(); ready("note-text")
            val expected = "Unchanged append\nBody\n"
            compose.onNodeWithTag("note-text").assertTextEquals(expected); caret("note-text", expected.length)
            scenario.recreate(); ready("note-text")
            compose.onNodeWithTag("note-text").assertTextEquals(expected); caret("note-text", expected.length)
            compose.onNodeWithTag("done").performClick(); ready("items")
            assertEquals(before, helper.getNoteById(id))
        }
    }

    @Test fun pickerKeyboardDownUpEnterAndEscapeReturnToList() {
        val second = helper.insertNote("Second picker row", "Preview")
        val first = helper.insertNote("First picker row", "Preview")
        launch(QuickCapture.APPEND_NOTE).use { scenario ->
            ready("pick-note-$first"); compose.onNodeWithTag("pick-note-$first").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_DOWN); compose.onNodeWithTag("pick-note-$second").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_UP); compose.onNodeWithTag("pick-note-$first").assertIsFocused()
            scenario.recreate(); ready("pick-note-$first")
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_ENTER); ready("note-text")
            compose.onNodeWithTag("note-text").assertTextEquals("Second picker row\nPreview\n")
            compose.onNodeWithTag("done").performClick(); ready("items")
            context.startActivity(intent(QuickCapture.APPEND_NOTE)); ready("note-picker")
            key(KeyEvent.KEYCODE_ESCAPE); ready("items")
            compose.onNodeWithTag("note-picker").assertDoesNotExist()
        }
    }

    @Test fun warmCaptureSavesDraftAndReplacesRatherThanStacksEditors() {
        launch(QuickCapture.NEW_NOTE).use { scenario ->
            ready("note-text"); compose.onNodeWithTag("note-text").performTextReplacement("Saved before capture")
            context.startActivity(intent(QuickCapture.NEW_TODO, "Warm task")); ready("task-text")
            assertEquals(1, helper.getNotePreviews().count { it.title == "Saved before capture" })
            scenario.onActivity {
                assertEquals("list", it.navController.currentDestination?.route)
                assertNull(it.navController.previousBackStackEntry)
                assertTrue(it.currentEditor is TaskScreen)
            }
            context.startActivity(intent(QuickCapture.NEW_NOTE)); ready("note-text")
            blank("note-text")
            assertEquals(1, helper.getTaskSummaries().count { it.task.text == "Warm task" })
            scenario.onActivity { assertEquals("list", it.navController.previousBackStackEntry?.destination?.route) }
        }
    }

    @Test fun captureIgnoresNonStringExtrasAndExternalIdsAndBoundsPrefill() {
        val id = helper.insertNote("Never external ID", "Original")
        val malformed = intent(QuickCapture.NEW_NOTE).putExtra(Intent.EXTRA_TEXT, 42)
            .putExtra("extra_note_id", id).putExtra(MainActivity.EXTRA_SECTION, "bad section type")
        ActivityScenario.launch<MainActivity>(malformed).use {
            ready("note-text"); blank("note-text")
            assertEquals("Original", helper.getNoteById(id)!!.content)
        }
        launch(QuickCapture.NEW_NOTE, "x".repeat(10_001)).use {
            ready("note-text"); compose.onNodeWithTag("note-text").assertTextEquals("x".repeat(10_000))
        }
    }

    @Test fun appendDoesNotDuplicateExistingTrailingNewline() {
        val id = helper.insertNote("Terminated", "Body\n")
        launch(QuickCapture.APPEND_NOTE, "Next").use {
            ready("pick-note-$id"); compose.onNodeWithTag("pick-note-$id").performClick(); ready("note-text")
            compose.onNodeWithTag("note-text").assertTextEquals("Terminated\nBody\nNext")
            caret("note-text", "Terminated\nBody\nNext".length)
        }
    }

    @Test fun failedDraftSaveBlocksIncomingCapture() {
        val id = helper.insertNote("Capture save failure", "Committed")
        val trigger = "capture_save_failure"
        try {
            launchNote(id).use { scenario ->
                compose.onNodeWithTag("note-text").performTextReplacement("Capture save failure\nUnsaved")
                helper.writableDatabase.execSQL("CREATE TRIGGER $trigger BEFORE UPDATE ON notes WHEN OLD.id=$id BEGIN SELECT RAISE(ABORT, 'fixture'); END")
                context.startActivity(intent(QuickCapture.NEW_TODO, "Must not open"))
                ready("error")
                compose.onNodeWithText(context.getString(R.string.capture_open_failed)).assertExists()
                compose.onNodeWithTag("note-text").assertTextEquals("Capture save failure\nUnsaved")
                compose.onNodeWithTag("task-text").assertDoesNotExist()
                assertEquals("Committed", helper.getNoteById(id)!!.content)
                scenario.recreate(); ready("note-text")
                compose.onNodeWithTag("note-text").assertTextEquals("Capture save failure\nUnsaved")
                compose.onNodeWithTag("task-text").assertDoesNotExist()
                helper.writableDatabase.execSQL("DROP TRIGGER $trigger")
                compose.onNodeWithTag("done").performClick(); ready("task-text")
                compose.onNodeWithTag("task-text").assertTextEquals("Must not open")
                assertEquals("Unsaved", helper.getNoteById(id)!!.content)
                compose.onNodeWithTag("done").performClick(); ready("items")
            }
        } finally { helper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $trigger") }
    }
}
