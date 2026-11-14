package com.example.notes

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListOverlayTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val helper = DatabaseHelper(InstrumentationRegistry.getInstrumentation().targetContext)
    private val notes = mutableListOf<Long>()
    private val tasks = mutableListOf<Long>()
    @After fun cleanup() {
        helper.deleteNotes(notes)
        tasks.forEach { helper.deleteTask(it) }
        helper.close()
    }

    @Test fun listsScrollPastFloatingButtonsAndOnlyHintWhenMoreIsBelow() {
        repeat(30) {
            notes.add(helper.insertNote("Overlay note $it", "Line of preview text"))
            tasks.add(checkNotNull(helper.insertTask("Overlay task $it", it % 2 == 0)))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10000) { compose.onAllNodesWithTag("note-${notes.last()}").fetchSemanticsNodes().isNotEmpty() }
            for (section in listOf("NOTES", "TASKS")) {
                compose.onNodeWithTag("section-$section").performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("items").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -100000f) }
                compose.onNodeWithTag("scroll-more").assertExists()
                val list = compose.onNodeWithTag("items").fetchSemanticsNode().boundsInRoot
                val fab = compose.onNodeWithTag("create").fetchSemanticsNode().boundsInRoot
                assertTrue("FAB overlays list instead of shrinking it", list.bottom > fab.bottom)
                compose.onNodeWithTag("items").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100000f) }
                compose.onNodeWithTag("scroll-more").assertDoesNotExist()
                val last = if (section == "NOTES") "note-${notes.first()}" else "task-${tasks.first()}"
                val row = compose.onNodeWithTag(last).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val buttonsTop = compose.onNodeWithTag(if (section == "TASKS") "delete-checked" else "create")
                    .fetchSemanticsNode().boundsInRoot.top
                assertTrue("Last row scrolls above every floating button", row.bottom <= buttonsTop)
            }
        }
    }
}
