package com.example.notes

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AuditRemediationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ids = mutableListOf<Long>()
    @After fun clean() { DatabaseHelper(context).use { it.deleteNotes(ids) } }

    @Test fun delayedCreationIgnoresRowOpensAndIsAbandonedAcrossHomeAndRecreation() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val id = DatabaseHelper(context).use { it.insertNote("race-${UUID.randomUUID()}", "keep") }; ids.add(id)
        val before = DatabaseHelper(context).use { it.getNotePreviews().map { n -> n.id }.toSet() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var owner: NoteCreation
            scenario.onActivity { activity ->
                owner = ViewModelProvider(activity)[NoteCreation::class.java]
                owner.insert = {
                    entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                    DatabaseHelper(context).use { it.insertNote("", "") }.also { synchronized(ids) { ids.add(it) } }
                }
            }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("note-$id").fetchSemanticsNodes().isNotEmpty() }
            try {
                compose.onNodeWithTag("create").performClick()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                compose.onNodeWithTag("note-$id").performClick()
                compose.onNodeWithTag("items").assertExists() // Conflicting editor never opened.
                scenario.moveToState(Lifecycle.State.CREATED) // Home/stop before the insert completes.
                scenario.recreate()
                release.countDown()
                compose.waitUntil(5000) { !owner.pending }
                scenario.moveToState(Lifecycle.State.RESUMED)
                compose.onNodeWithTag("items").assertExists()
                scenario.recreate()
                compose.onNodeWithTag("items").assertExists()
                assertEquals(before, DatabaseHelper(context).use { it.getNotePreviews().map { n -> n.id }.toSet() })
                assertEquals("keep", DatabaseHelper(context).use { it.getNoteById(id)!!.content })
            } finally { release.countDown() }
        }
    }

    @Test fun selectionEndpointsNormalizeOnlyOnRecreationAndSurviveFontChanges() {
        val id = DatabaseHelper(context).use { it.insertNote("selection-${UUID.randomUUID()}", "") }; ids.add(id)
        val raw = "  Title  \n  Body  "
        launchNote(id).use { scenario ->
            val node = compose.onNodeWithTag("note-text")
            node.performTextReplacement(raw)
            node.performSemanticsAction(SemanticsActions.SetSelection) { it(12, 3, false) }
            compose.waitForIdle()
            val state = android.os.Bundle()
            scenario.onActivity {
                instrumentation.callActivityOnSaveInstanceState(it, state)
                state.putAll(it.currentEditor!!.captureState())
            }
            assertEquals(6, state.getInt("selection_start")); assertEquals(1, state.getInt("selection_end"))
            scenario.onActivity { activity ->
                val field = NoteScreen::class.java.getDeclaredField("draft").apply { isAccessible = true }
                val live = field.get(activity.currentEditor) as androidx.compose.foundation.text.input.TextFieldState
                assertEquals(raw, live.text.toString()); assertEquals(TextRange(12, 3), live.selection)
            }
            scenario.recreate()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("note-text").fetchSemanticsNodes().isNotEmpty() }
            node.assertTextEquals("Title\nBody")
            assertEquals(TextRange(6, 1), node.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            // Changing settings recomposes the live field without normalizing/replacing it.
            node.performTextReplacement(raw)
            node.performSemanticsAction(SemanticsActions.SetSelection) { it(12, 12, false) }
            FontSettings(context).use { it.select(FontChoice.MONOSPACE) }
            compose.waitForIdle()
            node.assertTextEquals(raw)
            scenario.recreate()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("note-text").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(TextRange(6), node.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        }
    }

    @Test fun longSingleLineProjectionIsBoundedAndFullReadIsExact() {
        val name = "preview-${UUID.randomUUID()}.db"
        try { DatabaseHelper(context, name).use { helper ->
            val title = "T".repeat(50_000); val body = "B".repeat(50_000)
            val id = helper.insertNote(title, body)
            val preview = helper.getNotePreviews().single()
            assertEquals("T".repeat(200) + "…", preview.title)
            assertEquals("B".repeat(200) + "…", preview.content)
            DatabaseHelper(context, name).use { reopened ->
                assertEquals(title, reopened.getNoteById(id)!!.title)
                assertEquals(body, reopened.getNoteById(id)!!.content)
            }
            helper.updateNote(id, title, "Preview\n$body")
            assertEquals("Preview", helper.getNotePreviews().single().content)
            assertEquals("Preview\n$body", helper.getNoteById(id)!!.content)
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun noteOrderingIsMonotonicLocaleNeutralAndLegacyTiesAreStable() {
        val name = "note-order-${UUID.randomUUID()}.db"; val locale = Locale.getDefault()
        try { DatabaseHelper(context, name).use { helper ->
            val a = helper.insertNote("a", "☑ literal\nfull body"); val b = helper.insertNote("b")
            helper.writableDatabase.use { it.execSQL("UPDATE notes SET timestamp='2099-01-01 00:00:00'") }
            assertEquals(listOf(b, a), helper.getNotePreviews().map { it.id })
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            var previous = "2099-01-01 00:00:00"
            repeat(5) {
                assertEquals(1, helper.updateNote(a, "a", "☑ literal\nfull body"))
                val stamp = helper.getNoteById(a)!!.timestamp
                assertTrue(stamp.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}")))
                assertTrue(stamp > previous); previous = stamp
                assertEquals(a, helper.getNotePreviews().first().id)
                helper.updateNote(b, "b", "")
                previous = helper.getNoteById(b)!!.timestamp
            }
            val c = helper.insertNote("c")
            assertTrue(helper.getNoteById(c)!!.timestamp > previous)
            DatabaseHelper(context, name).use { reopened ->
                assertEquals(listOf(c, b, a), reopened.getNotePreviews().map { it.id })
                assertEquals("☑ literal\nfull body", reopened.getNoteById(a)!!.content)
            }
        } } finally { Locale.setDefault(locale); context.deleteDatabase(name) }
    }
}
