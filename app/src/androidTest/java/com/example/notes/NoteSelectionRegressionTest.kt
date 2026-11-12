package com.example.notes

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Run only on the dedicated Notes test AVD: fixtures never replace existing rows. */
@RunWith(AndroidJUnit4::class)
class NoteSelectionRegressionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val helper = DatabaseHelper(context)
    private val fixtureIds = mutableListOf<Long>()

    @After fun cleanup() { helper.deleteNotes(fixtureIds); helper.close() }

    @Test fun selectionSurvivesScrollingAndRecreationThenDeletesOnlySelectedNotes() {
        val prefix = "selection-${UUID.randomUUID()}"
        repeat(60) { fixtureIds.add(helper.insertNote("$prefix-$it", "Preview")) }
        val orderedIds = helper.getNotePreviews().map { it.id }
        val selectedIds = fixtureIds.take(2).toSet()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitLoaded()
            compose.onNodeWithTag("select-notes").performClick()
            selectedIds.forEach { id -> scrollTo(id); compose.onNodeWithTag("note-$id").performClick() }
            scrollTo(orderedIds.last())
            selectedIds.forEach { id -> scrollTo(id); compose.onNodeWithTag("note-$id").assertIsSelected() }
            scenario.recreate()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("delete-selected").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("loading").fetchSemanticsNodes().isEmpty() }
            selectedIds.forEach { id -> scrollTo(id); compose.onNodeWithTag("note-$id").assertIsSelected() }
            compose.onNodeWithTag("delete-selected").assertIsDisplayed().performClick()
            compose.waitUntil(20_000) { helper.getNotePreviews().size == orderedIds.size - selectedIds.size }
            selectedIds.forEach { assertNull(helper.getNoteById(it)) }
            fixtureIds.drop(2).forEach { assertNotNull(helper.getNoteById(it)) }
        }
    }
    @Test fun backCancelsSelectionBeforeNavigation() {
        fixtureIds.add(helper.insertNote("back selection", "Preview"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitLoaded()
            compose.onNodeWithTag("select-notes").performClick()
            scrollTo(fixtureIds.single())
            compose.onNodeWithTag("note-${fixtureIds.single()}").performClick()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("delete-selected").assertDoesNotExist()
            scenario.onActivity { assertFalse(it.isFinishing) }
            assertNotNull(helper.getNoteById(fixtureIds.single()))
        }
    }
    private fun awaitLoaded() = compose.waitUntil(20_000) {
        compose.onAllNodesWithTag("select-notes").fetchSemanticsNodes().isNotEmpty()
    }
    private fun scrollTo(id: Long) {
        compose.onNodeWithTag("items").performScrollToKey("note-$id")
    }
}
