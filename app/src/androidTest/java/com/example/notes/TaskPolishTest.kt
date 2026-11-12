package com.example.notes

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskPolishTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val helper = DatabaseHelper(context)
    private val ids = mutableListOf<Long>()
    @After fun cleanup() { ids.forEach { helper.deleteTask(it) }; helper.close() }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(context.getExternalFilesDir(null), name).outputStream().use {
            shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        shot.recycle()
    }
    private fun loaded(tag: String) = compose.waitUntil(10000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun layout(tag: String): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single()
    }
    private fun strike(tag: String, checked: Boolean) {
        assertEquals(if (checked) TextDecoration.LineThrough else TextDecoration.None, layout(tag).layoutInput.style.textDecoration)
    }
    private fun topRight(panel: String) {
        val gear = compose.onNodeWithTag("settings").assertHasClickAction().fetchSemanticsNode().boundsInRoot
        val bottom = compose.onNodeWithTag(panel).fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(gear.center.x > root.center.x)
        assertTrue(gear.bottom <= bottom.top)
        assertTrue(gear.top < root.center.y)
        compose.onNodeWithTag("settings").assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
        compose.onNodeWithTag("settings").assertWidthIsAtLeast(androidx.compose.ui.unit.Dp(48f))
        compose.onNode(hasTestTag("settings") and hasAnyAncestor(hasTestTag(panel))).assertDoesNotExist()
    }
    private fun editorActions() {
        compose.onNodeWithTag("settings").assertDoesNotExist()
        compose.onNodeWithTag("section-panel").assertDoesNotExist()
        compose.onNodeWithTag("create").assertDoesNotExist()
        for (tag in listOf("font-picker", "leave-input", "task-menu", "done")) {
            // Compare one settled IME frame, not a footer snapshot from before its animation.
            compose.waitUntil(10000) {
                val footer = compose.onNodeWithTag("task-actions").fetchSemanticsNode().boundsInRoot
                val control = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                control.top >= footer.top && control.bottom <= footer.bottom
            }
            val control = compose.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
            val footer = compose.onNodeWithTag("task-actions").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$tag belongs to footer", control.top >= footer.top && control.bottom <= footer.bottom)
        }
    }

    @Test fun shellSettingsAndFabStaySeparateOnBothSections() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (section in listOf("NOTES", "TASKS")) {
                compose.onNodeWithTag("section-$section").performClick()
                topRight("section-panel")
                val panel = compose.onNodeWithTag("section-panel").fetchSemanticsNode().boundsInRoot
                val fab = compose.onNodeWithTag("create").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val gear = compose.onNodeWithTag("settings").fetchSemanticsNode().boundsInRoot
                val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
                assertTrue(fab.bottom <= panel.top)
                assertTrue(fab.center.x > root.center.x)
                assertTrue(gear.bottom < fab.top)
                scenario.recreate()
                compose.onNodeWithTag("section-$section").assertIsSelected()
                topRight("section-panel")
            }
            screenshot("polish-shell-settings.png")
        }
    }

    @Test fun editorSettingsAndActionsStayClearOfIme() {
        launchTask().use { scenario ->
            loaded("task-text")
            compose.onNodeWithTag("task-text").performClick()
            var ime = 0
            compose.waitUntil(10000) {
                scenario.onActivity { ime = it.window.decorView.rootWindowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom }
                ime > 0
            }
            editorActions()
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val done = compose.onNodeWithTag("done").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Done is above IME", done.bottom <= root.bottom - ime + 1)
            screenshot("polish-editor-ime.png")
        }
    }

    @Test fun shellStrikeAlignmentGroupingUncheckTopAndRecreation() {
        val a = helper.insertTask("first\nsecond")!!; ids.add(a)
        val b = helper.insertTask("other")!!; ids.add(b)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithTag("section-TASKS").performClick()
            loaded("task-$a"); topRight("section-panel")
            strike("task-label-$a", false)
            fun top(id: Long) = compose.onNodeWithTag("task-$id").fetchSemanticsNode().boundsInRoot.top
            assertTrue(top(b) < top(a))
            val check = compose.onNodeWithTag("task-completed-$a", true)
            val label = compose.onNodeWithTag("task-label-$a", true).fetchSemanticsNode().boundsInRoot
            val text = layout("task-label-$a")
            assertEquals(label.top + (text.getLineTop(0) + text.getLineBottom(0)) / 2,
                check.fetchSemanticsNode().boundsInRoot.center.y, 2f)
            check.performClick()
            compose.waitUntil(10000) { helper.getTaskById(a)!!.completed }
            compose.waitUntil(10000) { layout("task-label-$a").layoutInput.style.textDecoration == TextDecoration.LineThrough }
            check.assertIsOn(); strike("task-label-$a", true)
            screenshot("polish-shell-checked.png")
            assertTrue(top(b) < top(a))
            check.performClick()
            compose.waitUntil(10000) { !helper.getTaskById(a)!!.completed }
            compose.waitUntil(10000) { top(a) < top(b) }
            check.assertIsOff(); strike("task-label-$a", false)
            scenario.recreate(); loaded("task-$a")
            assertTrue(top(a) < top(b))
            assertEquals(setOf(a, b), helper.getTaskSummaries().map { it.task.id }.toSet())
        }
    }

    @Test fun editorStrikeUnstrikeFontAndFooterPersist() {
        val a = helper.insertTask("parent\nsecond")!!; ids.add(a)
        val old = FontSettings(context).use { it.choice }
        try {
            launchTask(a).use { scenario ->
                loaded("task-text"); editorActions()
                compose.onNodeWithTag("font-picker").performClick()
                compose.onNodeWithTag("font-MONOSPACE").performClick()
                compose.onNode(hasText("Done") and hasAnyAncestor(isDialog())).performClick()
                assertEquals(androidx.compose.ui.text.font.FontFamily.Monospace, layout("task-text").layoutInput.style.fontFamily)
                strike("task-text", false)
                compose.onNodeWithTag("parent-completed").performClick().assertIsOn()
                strike("task-text", true)
                screenshot("polish-editor-checked.png")
                scenario.recreate(); loaded("task-text")
                strike("task-text", true)
                compose.onNodeWithTag("parent-completed").performClick().assertIsOff()
                strike("task-text", false)
                compose.onNodeWithTag("done").performClick()
            }
            assertFalse(helper.getTaskById(a)!!.completed)
        } finally { FontSettings(context).use { it.select(old) } }
    }
}
