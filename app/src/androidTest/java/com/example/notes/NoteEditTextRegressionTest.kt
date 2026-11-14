package com.example.notes

import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Original audit names retained; all UI assertions now exercise the active Compose input. */
@RunWith(AndroidJUnit4::class)
class NoteEditTextRegressionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val helper = DatabaseHelper(context)
    private val ids = mutableListOf<Long>()
    @After fun cleanup() { helper.deleteNotes(ids); helper.close() }
    private fun editor(title: String = "Title", body: String = "body", test: (ActivityScenario<MainActivity>, Long) -> Unit) {
        val id = helper.insertNote(title, body); ids.add(id)
        launchNote(id).use { test(it, id) }
    }
    private fun input() = compose.onNodeWithTag("note-text")
    private fun replace(text: String) { input().performTextReplacement(text); compose.waitForIdle() }
    private fun layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        input().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
    private fun strikeCount() = layout().layoutInput.text.spanStyles.count { it.item.textDecoration == TextDecoration.LineThrough }
    private fun leaveInput(scenario: ActivityScenario<MainActivity>) {
        // Move focus without activating a footer action or changing the caret/checklist.
        input().performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithTag("done").performSemanticsAction(SemanticsActions.RequestFocus)
        input().assertIsNotFocused()
        scenario.onActivity {
            androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(10000) {
            var hidden = false
            scenario.onActivity { hidden = !it.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime()) }
            hidden
        }
    }
    private fun tap(scenario: ActivityScenario<MainActivity>, line: Int, duration: Long = 10, move: Boolean = false, back: Boolean = false, cancel: Boolean = false, multi: Boolean = false, atEnd: Boolean = false, startAbove: Boolean = false) {
        leaveInput(scenario)
        val l = layout()
        val p = Offset(if (atEnd) l.getHorizontalPosition(l.getLineEnd(line), false) + 4f else 8f, (l.getLineTop(line) + l.getLineBottom(line)) / 2)
        input().performTouchInput {
            down(if (startAbove) p - Offset(0f, 100f) else p)
            if (startAbove) moveTo(p)
            if (multi) down(1, p + Offset(30f, 0f))
            if (move) { moveTo(p + Offset(100f, 0f)); if (back) moveTo(p) }
            advanceEventTime(duration)
            if (cancel) cancel() else { if (multi) up(1); up() }
        }
        compose.waitForIdle()
    }
    @Test fun leadingNewlineKeepsBlankTitleAndDoesNotBoldFollowingText() = editor { scenario, id ->
        replace("\nTitle\nbody")
        assertEquals("" to "Title\nbody", splitNote("\nTitle\nbody"))
        assertTrue(layout().layoutInput.text.spanStyles.none { it.item.fontWeight == FontWeight.Bold })
        replace("\nBody\nSecond")
        scenario.recreate()
        input().assertTextEquals("\nBody\nSecond")
        assertEquals("", helper.getNoteById(id)!!.title)
    }
    @Test fun emptyCheckboxMarkerSurvivesReloadAndCanBeTypedAndChecked() = editor("Title", "☐") { scenario, _ ->
        input().assertTextEquals("Title\n☐ ")
        replace("Title\n☐ task")
        tap(scenario, 1)
        input().assertTextEquals("Title\n☑ task")
        assertEquals(1, strikeCount())
    }
    @Test fun emptyCheckboxOnFirstLineAlsoSurvivesReload() {
        for (marker in listOf("☐", "☑")) editor(marker, "") { scenario, _ ->
            input().assertTextEquals("$marker ")
            scenario.recreate()
            input().assertTextEquals("$marker ")
            replace("$marker milk"); tap(scenario, 0)
            input().assertTextEquals("${if (marker == "☐") "☑" else "☐"} milk")
        }
    }
    @Test fun removingCheckedPrefixClearsStrikethrough() = editor("Title", "☑ task") { _, _ ->
        assertEquals(1, strikeCount())
        replace("Title\ntask")
        assertEquals(0, strikeCount())
        replace("Other")
        assertEquals(1, layout().layoutInput.text.spanStyles.size)
        replace("\nOther")
        assertTrue(layout().layoutInput.text.spanStyles.isEmpty())
    }
    @Test fun onlyStationaryShortCheckboxTapToggles() = editor("Title", "☐ task") { scenario, _ ->
        tap(scenario, 1); input().assertTextEquals("Title\n☑ task")
        tap(scenario, 1); input().assertTextEquals("Title\n☐ task"); assertEquals(0, strikeCount())
        tap(scenario, 1, move = true); input().assertTextEquals("Title\n☐ task")
        tap(scenario, 1, move = true, back = true); input().assertTextEquals("Title\n☐ task")
        tap(scenario, 1, duration = 600); input().assertTextEquals("Title\n☐ task")
        tap(scenario, 1, cancel = true); input().assertTextEquals("Title\n☐ task")
        tap(scenario, 1, startAbove = true); input().assertTextEquals("Title\n☐ task")
        tap(scenario, 1, multi = true); input().assertTextEquals("Title\n☐ task")
        replace("Title\n☐ "); tap(scenario, 1, atEnd = true); input().assertTextEquals("Title\n☐ ")
        input().assertIsFocused().performTextInput("milk")
        input().assertTextEquals("Title\n☐ milk")
    }
    @Test fun insertionUsesCurrentLineAndLongPressStillSelectsOrdinaryText() = editor { scenario, _ ->
        compose.onNodeWithTag("insert-checkbox").performClick()
        input().assertIsFocused().assertTextEquals("☐ Title\nbody").performTextInput("milk")
        input().assertTextEquals("☐ milkTitle\nbody")
        replace("ordinary selection text")
        tap(scenario, 0, duration = 600)
        val range = input().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange]
        assertTrue("Native long press selects text", range.start != range.end)
    }
    @Test fun checkboxHitTestingUsesScrolledTextCoordinates() = editor("Title", "line\n".repeat(40) + "☐ scrolled") { scenario, _ ->
        leaveInput(scenario)
        val metrics = layout()
        val line = metrics.lineCount - 1
        val halfLine = (metrics.getLineBottom(line) - metrics.getLineTop(line)) / 2
        compose.onNodeWithTag("note-viewport").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100000f) }
        val inputPosition = input().fetchSemanticsNode().positionInRoot
        val viewportBounds = compose.onNodeWithTag("note-viewport").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("note-viewport").performTouchInput {
            click(Offset(60f, inputPosition.y - viewportBounds.top + metrics.getLineTop(line) + halfLine))
        }
        input().assertTextContains("☑ scrolled", substring = true)
        assertEquals(1, strikeCount())
    }

}
