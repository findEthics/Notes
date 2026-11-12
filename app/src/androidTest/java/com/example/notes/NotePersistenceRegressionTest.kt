package com.example.notes

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Rule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NotePersistenceRegressionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun text() = compose.onNodeWithTag("note-text")
    private fun replace(value: String) { text().performTextReplacement(value); compose.waitForIdle() }
    private fun done() { compose.onNodeWithTag("done").performClick() }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val helper = DatabaseHelper(context)
    private val prefix = "regression-${UUID.randomUUID()}"
    private val fixtureIds = mutableListOf<Long>()

    @After
    fun cleanup() {
        val replacementIds = helper.getNotePreviews()
            .filter { it.title.startsWith(prefix) || it.content.startsWith(prefix) }.map { it.id }
        helper.deleteNotes(fixtureIds + replacementIds)
        helper.close()
    }

    @Test
    fun blankPauseThenTypingRetainsReplacementIdAcrossRecreationAndDone() {
        val originalId = insert("", "")
        editor(originalId).use { scenario ->
            scenario.moveToState(Lifecycle.State.CREATED)
            assertNull(helper.getNoteById(originalId))
            scenario.moveToState(Lifecycle.State.RESUMED)
            replace("$prefix\nBody")
            scenario.moveToState(Lifecycle.State.CREATED)
            val replacement = helper.getNotePreviews().single { it.title == prefix }
            assertNotEquals(originalId, replacement.id)

            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.recreate()
            text().assertTextEquals("$prefix\nBody")
            replace("$prefix\nBody\nSecond")
            done()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val saved = helper.getNoteById(replacement.id)!!
            assertEquals("Body\nSecond", saved.content)
            assertEquals(1, helper.getNotePreviews().count { it.title == prefix })
        }
    }

    @Test
    fun clearingExistingNoteThenBackgroundingCanBeEditedAndSavedWithBack() {
        val originalId = insert(prefix, "Original")
        editor(originalId).use { scenario ->
            replace(" \n ")
            scenario.moveToState(Lifecycle.State.CREATED)
            assertNull(helper.getNoteById(originalId))
            scenario.moveToState(Lifecycle.State.RESUMED)
            replace("$prefix\nRestored")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
        val saved = helper.getNotePreviews().single { it.title == prefix }
        assertNotEquals(originalId, saved.id)
        assertEquals("Restored", helper.getNoteById(saved.id)!!.content)
    }

    @Test
    fun blankTitleAndEmptyCheckboxSurviveSaveReopenTypingAndBackgroundSave() {
        val id = insert("", "")
        editor(id).use { scenario ->
            replace("\n$prefix\nSecond\n")
            compose.onNodeWithTag("insert-checkbox").performClick()
            done()
        }
        assertEquals("", helper.getNoteById(id)!!.title)
        editor(id).use { scenario ->
            text().assertTextEquals("\n$prefix\nSecond\n☐ ")
            replace("\n$prefix\nSecond\n☐ milk")
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals("$prefix\nSecond\n☐ milk", helper.getNoteById(id)!!.content)
        }
    }

    @Test
    fun listProjectionReturnsOnlyFirstBodyLineWithoutChangingStoredBody() {
        val body = "Preview\n" + "x".repeat(50_000)
        val id = insert(prefix, body)
        val leadingNewline = insert("$prefix-blank-preview", "\nBody")
        val singleLine = insert("$prefix-single", "One line")
        val previews = helper.getNotePreviews().associateBy { it.id }
        assertEquals("Preview", previews.getValue(id).content)
        assertEquals("", previews.getValue(leadingNewline).content)
        assertEquals("One line", previews.getValue(singleLine).content)
        assertEquals(body, helper.getNoteById(id)!!.content)
    }

    @Test
    fun unsupportedUpgradeRefusesToOpenAndPreservesVersionOneNote() {
        val name = "$prefix-upgrade.db"
        fun fixture(version: Int) = object : SQLiteOpenHelper(context, name, null, version) {
            override fun onCreate(db: SQLiteDatabase) = helper.onCreate(db)
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
                helper.onUpgrade(db, oldVersion, newVersion)
        }
        try {
            fixture(1).use {
                it.writableDatabase.execSQL(
                    "INSERT INTO notes(id, title, content, timestamp) VALUES(7, ?, ?, ?)",
                    arrayOf(prefix, "Keep this note", "2026-01-01 00:00:00")
                )
            }
            assertThrows(SQLiteException::class.java) {
                fixture(3).use { it.writableDatabase }
            }
            fixture(1).use {
                assertEquals(1, it.readableDatabase.version)
                it.readableDatabase.rawQuery("SELECT id, title, content FROM notes", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(7L, cursor.getLong(0))
                    assertEquals(prefix, cursor.getString(1))
                    assertEquals("Keep this note", cursor.getString(2))
                    assertFalse(cursor.moveToNext())
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun deletedNonblankRowReinsertsOnceAndStateCaptureRetainsIdAndSelection() {
        val id = insert(prefix, "Original")
        editor(id).use { scenario ->
            replace("$prefix\nReplacement")
            helper.deleteNotes(listOf(id))
            val state = android.os.Bundle()
            text().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetSelection) { it(2, 5, false) }
            scenario.onActivity {
                InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(it, state)
                state.putAll(it.currentEditor!!.captureState())
            }
            val replacement = state.getLong(NoteScreen.EXTRA_NOTE_ID)
            fixtureIds.add(replacement)
            assertNotEquals(id, replacement)
            assertEquals(2, state.getInt("selection_start")); assertEquals(5, state.getInt("selection_end"))
            assertFalse(state.containsKey("unsaved_note"))
            repeat(2) { scenario.moveToState(Lifecycle.State.CREATED); scenario.moveToState(Lifecycle.State.RESUMED) }
            scenario.recreate()
            text().assertTextEquals("$prefix\nReplacement")
            assertEquals(androidx.compose.ui.text.TextRange(2, 5), text().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange])
            done()
            assertEquals("Replacement", helper.getNoteById(replacement)!!.content)
            assertEquals(1, helper.getNotePreviews().count { it.title == prefix })
        }
    }

    @Test
    fun failedSaveBlocksDoneBackAndKeyboardCreationAndRetainsDraftAcrossRecreation() {
        val id = insert(prefix, "Committed")
        val trigger = "note_editor_failure"
        fun fail(enabled: Boolean) {
            helper.writableDatabase.apply {
                execSQL("DROP TRIGGER IF EXISTS $trigger")
                if (enabled) execSQL("CREATE TRIGGER $trigger BEFORE UPDATE ON notes WHEN OLD.id=$id BEGIN SELECT RAISE(ABORT, 'fixture write failure'); END")
                close()
            }
        }
        try {
            editor(id).use { scenario ->
                replace("$prefix\nUnsaved")
                fail(true)
                compose.onNodeWithTag("done").performClick()
                compose.onNodeWithTag("error").assertExists()
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                text().assertTextEquals("$prefix\nUnsaved")
                compose.onNodeWithTag("leave-input").performClick()
                for (code in listOf(android.view.KeyEvent.KEYCODE_N, android.view.KeyEvent.KEYCODE_T)) {
                    compose.waitForIdle()
                    scenario.onActivity { activity ->
                        assertTrue(activity.dispatchKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN,
                            code, 0, android.view.KeyEvent.META_ALT_ON)))
                    }
                    text().assertTextEquals("$prefix\nUnsaved")
                }
                text().assertTextEquals("$prefix\nUnsaved")
                assertEquals("Committed", helper.getNoteById(id)!!.content)
                scenario.recreate()
                text().assertTextEquals("$prefix\nUnsaved")
                compose.onNodeWithTag("error").assertExists()
                fail(false)
                done()
            }
            assertEquals("Unsaved", helper.getNoteById(id)!!.content)
        } finally { fail(false) }
    }

    private fun insert(title: String, content: String): Long =
        helper.insertNote(title, content).also { fixtureIds.add(it) }

    private fun editor(id: Long): ActivityScenario<MainActivity> = launchNote(id)
}
