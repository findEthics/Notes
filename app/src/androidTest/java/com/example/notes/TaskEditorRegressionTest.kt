package com.example.notes

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Dedicated test AVD only; fixture IDs are the only deleted rows. */
@RunWith(AndroidJUnit4::class)
class TaskEditorRegressionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val helper = DatabaseHelper(context)
    private val ids = mutableSetOf<Long>()
    @After fun cleanup() { ids.forEach { helper.deleteTask(it) }; helper.close() }
    private fun loaded() = compose.waitUntil(20_000) {
        compose.onAllNodesWithTag("task-text").fetchSemanticsNodes().isNotEmpty()
    }
    private fun key(code: Int, meta: Int = 0, repeat: Int = 0) {
        compose.waitForIdle()
        val now = android.os.SystemClock.uptimeMillis()
        if (code == KeyEvent.KEYCODE_ENTER && meta == 0) {
            // The emulator IME turns injected Enter into commitText; exercise the hardware path.
            instrumentation.runOnMainSync {
                val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().single()
                activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, repeat, meta))
                activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
            }
        } else {
            instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, repeat, meta))
            instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
        }
        instrumentation.waitForIdleSync()
        compose.waitForIdle()
    }

    @Test fun doneBackgroundBackAndRecreationRetainIdAndEdits() {
        val text = "task-${UUID.randomUUID()}"
        launchTask().use { scenario ->
            loaded()
            compose.onNodeWithTag("task-text").performTextReplacement(text)
            compose.onNodeWithTag("parent-completed").performClick()
            scenario.moveToState(Lifecycle.State.CREATED)
            val saved = helper.getTaskSummaries().single { it.task.text == text }.task
            ids.add(saved.id)
            assertTrue(saved.completed)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.recreate(); loaded()
            scenario.onActivity {
                assertEquals("list", it.navController.currentDestination?.route)
                assertEquals(saved.id, (it.currentEditor as TaskScreen).editingTaskId)
            }
            compose.onNodeWithTag("task-text").assertTextContains(text).performTextReplacement("task edited")
            // Semantics replacement must reach the field's IME buffer before native Back.
            compose.waitForIdle()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("task-text").fetchSemanticsNodes().isEmpty() }
            instrumentation.waitForIdleSync()
            assertEquals("task edited", helper.getTaskById(saved.id)!!.text)
            assertTrue(helper.getTaskById(saved.id)!!.completed)
            assertEquals(1, helper.getTaskSummaries().count { it.task.id == saved.id })
        }
        val id = ids.single()
        launchTask(id).use {
            loaded()
            compose.onNodeWithTag("task-text").performTextReplacement("edited parent")
            compose.onNodeWithTag("done").performClick()
        }
        assertEquals("edited parent", helper.getTaskById(id)!!.text)
    }

    @Test fun nativeEditsAfterRecreationSurviveEverySaveBoundaryWithoutChangingId() {
        val id = helper.insertTask("native parent")!!; ids.add(id)
        fun stored(parent: String) {
            assertEquals(parent, helper.getTaskById(id)!!.text)
            assertFalse(helper.getTaskById(id)!!.completed)
            assertEquals(1, helper.getTaskSummaries().count { it.task.id == id })
        }
        fun nativeReplace(tag: String, value: String) {
            compose.onNodeWithTag("items").performScrollToNode(hasTestTag(tag))
            // Acquire focus explicitly, then synchronize Android and Compose input queues.
            compose.onNodeWithTag(tag).performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { assertTrue(it()) }
                .assertIsFocused()
            instrumentation.waitForIdleSync()
            compose.waitForIdle()
            fun send(code: Int, action: Int, meta: Int = 0) {
                val now = android.os.SystemClock.uptimeMillis()
                instrumentation.sendKeySync(KeyEvent(now, now, action, code, 0, meta))
                instrumentation.waitForIdleSync()
                compose.waitForIdle()
            }
            send(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_DOWN, KeyEvent.META_CTRL_ON)
            send(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN, KeyEvent.META_CTRL_ON)
            send(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP, KeyEvent.META_CTRL_ON)
            send(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_UP)
            compose.waitUntil(5000) {
                val config = compose.onNodeWithTag(tag).fetchSemanticsNode().config
                val range = config[SemanticsProperties.TextSelectionRange]
                range.min == 0 && range.max == config[SemanticsProperties.EditableText].length
            }
            val selected = compose.onNodeWithTag(tag).fetchSemanticsNode().config
            val range = selected[SemanticsProperties.TextSelectionRange]
            assertEquals(0, range.min)
            assertEquals(selected[SemanticsProperties.EditableText].length, range.max)
            // Native injection returning does not prove Compose applied the edit.
            // Verify each character and its collapsed cursor before dispatching the next.
            val keyboard = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
            value.forEachIndexed { index, character ->
                val events = checkNotNull(keyboard.getEvents(charArrayOf(character)))
                events.forEach { event ->
                    instrumentation.sendKeySync(event)
                    instrumentation.waitForIdleSync()
                    compose.waitForIdle()
                }
                val prefix = value.take(index + 1)
                compose.waitUntil(5000) {
                    val config = compose.onNodeWithTag(tag).fetchSemanticsNode().config
                    val cursor = config[SemanticsProperties.TextSelectionRange]
                    config[SemanticsProperties.EditableText].text == prefix &&
                        cursor.min == prefix.length && cursor.max == prefix.length
                }
            }
            compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText, AnnotatedString(value)))
        }
        for ((index, boundary) in listOf("Done", "Back", "background", "recreation").withIndex()) {
            val parent = "parent$index"
            launchTask(id).use { scenario ->
                loaded(); scenario.recreate(); loaded()
                nativeReplace("task-text", parent)
                when (boundary) {
                    "Done" -> compose.onNodeWithTag("done").performClick()
                    "Back" -> {
                        compose.waitForIdle()
                        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                    }
                    "background" -> repeat(3) {
                        compose.waitForIdle()
                        scenario.moveToState(Lifecycle.State.CREATED)
                        stored(parent)
                        scenario.moveToState(Lifecycle.State.RESUMED)
                    }
                    "recreation" -> repeat(3) { scenario.recreate(); loaded(); stored(parent) }
                }
                if (boundary == "Done" || boundary == "Back") {
                    compose.waitUntil(5000) { compose.onAllNodesWithTag("task-text").fetchSemanticsNodes().isEmpty() }
                }
            }
            stored(parent)
            launchTask(id).use {
                loaded()
                compose.onNodeWithTag("task-text").assert(SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText, AnnotatedString(parent)))
            }
            stored(parent)
        }
    }

    @Test fun blankDraftCreatesNoRecordAndBlankExistingDeletesThenCanBeReinserted() {
        val before = helper.getTaskSummaries().map { it.task.id }
        launchTask().use { loaded(); compose.onNodeWithTag("done").performClick() }
        assertEquals(before, helper.getTaskSummaries().map { it.task.id })
        val id = helper.insertTask("keep")!!; ids.add(id)
        launchTask(id).use { scenario ->
            loaded(); compose.onNodeWithTag("task-text").performTextReplacement("   ")
            key(KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON)
            assertNull(helper.getTaskById(id))
            scenario.recreate(); loaded()
            compose.onNodeWithTag("task-text").assert(SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText, AnnotatedString("")))
            compose.onNodeWithTag("task-text").performTextReplacement("recovered")
            compose.onNodeWithTag("done").performClick()
        }
        val replacement = helper.getTaskSummaries().single { it.task.text == "recovered" }.task
        ids.add(replacement.id)
        assertNotEquals(id, replacement.id)
    }

    @Test fun taskDeletionIsReachableFromListAfterClosingInlineEditor() {
        val id = helper.insertTask("delete fixture")!!; ids.add(id)
        launchTask(id).use {
            loaded(); key(KeyEvent.KEYCODE_ESCAPE)
            compose.onNodeWithTag("task-text").assertDoesNotExist()
            compose.onNodeWithTag("task-$id").performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { assertTrue(it()) }
            key(KeyEvent.KEYCODE_FORWARD_DEL)
            compose.onNodeWithTag("task-$id").assertDoesNotExist()
            // Undo defers the actual write until dismissal or stop.
            compose.onNodeWithContentDescription("Dismiss").performClick()
            compose.waitUntil(5000) { helper.getTaskById(id) == null }
        }
        assertNull(helper.getTaskById(id))
    }

    @Test fun longPressSavesAndClosesInlineEditorBeforeStagingDelete() {
        val editing = helper.insertTask("editing")!!; ids.add(editing)
        val target = helper.insertTask("long press target")!!; ids.add(target)
        launchTask(editing).use { scenario ->
            loaded()
            compose.onNodeWithTag("task-text").performTextReplacement("saved before delete")
            compose.onNodeWithTag("items").performScrollToNode(hasTestTag("task-$target"))
            compose.onNodeWithTag("task-$target").performTouchInput { longClick() }
            compose.onNodeWithTag("task-text").assertDoesNotExist()
            scenario.onActivity { assertNull(it.currentEditor) }
            assertEquals("saved before delete", helper.getTaskById(editing)!!.text)
            compose.onNodeWithText("Deleted 1").assertIsDisplayed()
            compose.onNodeWithTag("task-$target").assertDoesNotExist()
            assertNotNull(helper.getTaskById(target))
            compose.onNodeWithContentDescription("Dismiss").performClick()
            compose.waitUntil(10000) { helper.getTaskById(target) == null }
            scenario.recreate()
            compose.onNodeWithTag("task-text").assertDoesNotExist()
            assertNull(helper.getTaskById(target))
        }
    }

    @Test fun realKeyEventsGateInputFocusAndReplaceEditorAfterSaving() {
        val id = helper.insertTask("key fixture")!!; ids.add(id)
        launchTask(id).use { scenario ->
            loaded()
            compose.onNodeWithTag("task-text").performClick().performTextReplacement("key edited")
            val monitor = navigationMonitor(TaskScreen::class.java.name)
            fun send(code: Int, meta: Int, repeat: Int = 0) {
                // Native injection bypasses Compose test synchronization; apply edits/focus first.
                compose.waitForIdle()
                val now = android.os.SystemClock.uptimeMillis()
                instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, repeat, meta))
                instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
                instrumentation.waitForIdleSync()
            }
            send(KeyEvent.KEYCODE_T, KeyEvent.META_ALT_ON)
            send(KeyEvent.KEYCODE_N, KeyEvent.META_SHIFT_ON)
            compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("task-text") and hasText("N", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("task-text").assertTextContains("N", substring = true)
                .performTextReplacement("key edited")
            instrumentation.waitForIdleSync()
            assertEquals(0, monitor.hits)
            key(KeyEvent.KEYCODE_TAB)
            compose.onNodeWithTag("task-text").assertIsNotFocused()
            compose.onNodeWithTag("done").assertIsFocused()
            send(KeyEvent.KEYCODE_T, KeyEvent.META_ALT_ON, 1)
            send(KeyEvent.KEYCODE_T, KeyEvent.META_ALT_ON or KeyEvent.META_CTRL_ON)
            assertEquals(0, monitor.hits)
            send(KeyEvent.KEYCODE_T, KeyEvent.META_ALT_ON)
            val next = monitor.await()
            assertNotNull(next)
            assertEquals("key edited", helper.getTaskById(id)!!.text)
            compose.onNodeWithTag("task-text").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            instrumentation.runOnMainSync { next!!.closeEditor() }
            monitor.close()
        }
    }

    @Test fun enterSavesThenShowsBlankInSameActivityAndBlankEnterCloses() {
        val id = helper.insertTask("first")!!; ids.add(id)
        val surrounding = helper.insertTask("surrounding row")!!; ids.add(surrounding)
        launchTask(id).use { scenario ->
            loaded(); compose.onNodeWithTag("task-text").performTextReplacement("first edited")
            compose.onNodeWithTag("task-$surrounding").assertIsDisplayed()
            scenario.onActivity { assertEquals("list", it.navController.currentDestination?.route) }
            key(KeyEvent.KEYCODE_ENTER)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertEquals("first edited", helper.getTaskById(id)!!.text)
            compose.onNodeWithTag("task-text").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))).assertIsFocused()
            compose.onNodeWithTag("task-$id").assertIsDisplayed()
            compose.onNodeWithTag("task-$surrounding").assertIsDisplayed()
            scenario.onActivity { assertEquals(-1L, (it.currentEditor as TaskScreen).editingTaskId) }
            scenario.recreate(); loaded()
            compose.onNodeWithTag("task-text").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.onNodeWithTag("task-text").performTextReplacement("next inline task")
            key(KeyEvent.KEYCODE_ENTER)
            val next = helper.getTaskSummaries().single { it.task.text == "next inline task" }.task
            ids.add(next.id)
            assertNotEquals(id, next.id)
            compose.onNodeWithTag("task-${next.id}").assertIsDisplayed()
            compose.onNodeWithTag("task-text").assert(SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText, AnnotatedString(""))).assertIsFocused()
            key(KeyEvent.KEYCODE_ENTER)
            compose.waitUntil(5000) { compose.onAllNodesWithTag("task-text").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("items").assertIsDisplayed()
        }
    }

    @Test fun newInlineEnterAndControlSShowSavedRowsBeforeComposerCloses() {
        launchTask().use { scenario ->
            loaded()
            compose.onNodeWithTag("task-text").performClick().assertIsFocused()
            scenario.onActivity { it.window.insetsController?.show(android.view.WindowInsets.Type.ime()) }
            compose.waitUntil(5000) {
                var visible = false
                scenario.onActivity {
                    visible = it.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
                }
                visible
            }
            for ((code, meta) in listOf(KeyEvent.KEYCODE_ENTER to 0, KeyEvent.KEYCODE_S to KeyEvent.META_CTRL_ON)) {
                val text = "inline-visible-${UUID.randomUUID()}"
                compose.onNodeWithTag("task-text").performTextReplacement(text)
                key(code, meta)
                val saved = helper.getTaskSummaries().single { it.task.text == text }.task
                ids.add(saved.id)
                compose.waitUntil(5000) {
                    compose.onAllNodesWithTag("task-${saved.id}").fetchSemanticsNodes().isNotEmpty()
                }
                // Do not scroll or close the composer: the saved row must already be in its viewport.
                compose.onNodeWithTag("task-${saved.id}").assertIsDisplayed()
                compose.onNodeWithTag("task-label-${saved.id}", useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithTag("task-text").assertIsDisplayed().assertIsFocused().assert(
                    SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
                scenario.onActivity { assertEquals(-1L, (it.currentEditor as TaskScreen).editingTaskId) }
            }
        }
    }

    @Test fun enterOnBlankExistingDeletesAndCloses() {
        val id = helper.insertTask("delete with enter")!!; ids.add(id)
        launchTask(id).use { scenario ->
            loaded(); compose.onNodeWithTag("task-text").performTextReplacement(" \n ")
            key(KeyEvent.KEYCODE_ENTER)
            compose.waitUntil(5000) { compose.onAllNodesWithTag("task-text").fetchSemanticsNodes().isEmpty() }
            assertNull(helper.getTaskById(id))
        }
    }

    @Test fun shiftEnterInsertsNewlineAndControlSSavesWithoutClosing() {
        val id = helper.insertTask("line")!!; ids.add(id)
        launchTask(id).use { scenario ->
            loaded(); compose.onNodeWithTag("task-text").performTextReplacement("line")
            // The emulator IME strips Shift when converting injected Enter to commitText("\n").
            scenario.onActivity {
                val now = android.os.SystemClock.uptimeMillis()
                it.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, KeyEvent.META_SHIFT_ON))
                it.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, KeyEvent.META_SHIFT_ON))
            }
            compose.onNodeWithTag("task-text").assertTextEquals("line\n")
            key(KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertEquals("line\n", helper.getTaskById(id)!!.text)
            fun imeEnter() {
                scenario.onActivity {
                    checkNotNull(it.window.decorView.findFocus()
                        .onCreateInputConnection(android.view.inputmethod.EditorInfo())).commitText("\n", 1)
                }
                compose.waitForIdle()
            }
            imeEnter()
            assertEquals("", compose.onNodeWithTag("task-text").fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
            assertEquals("line\n", helper.getTaskById(id)!!.text)
            imeEnter()
            compose.onNodeWithTag("task-text").assertDoesNotExist()
        }
    }

    @Test fun escapeAndAltEnterSaveAndClose() {
        for ((code, meta) in listOf(KeyEvent.KEYCODE_ESCAPE to 0,
            KeyEvent.KEYCODE_ENTER to KeyEvent.META_ALT_ON)) {
            val id = helper.insertTask("close")!!; ids.add(id)
            launchTask(id).use { scenario ->
                loaded(); compose.onNodeWithTag("task-text").performTextReplacement("saved close")
                key(code, meta)
                compose.waitUntil(5000) { compose.onAllNodesWithTag("task-text").fetchSemanticsNodes().isEmpty() }
                assertEquals("saved close", helper.getTaskById(id)!!.text)
            }
        }
    }

    @Test fun activeFontSettingUpdatesImmediatelyAndSurvivesRecreation() {
        val previous = FontSettings(context).use { it.choice }
        try {
            launchTask().use { scenario ->
                loaded()
                compose.onNodeWithTag("settings").performClick()
                compose.onNodeWithTag("font-MONOSPACE").performClick().assertIsSelected()
                assertEquals(FontChoice.MONOSPACE, FontSettings(context).use { it.choice })
                compose.onNode(hasText("Done") and hasAnyAncestor(isDialog())).performClick()
                scenario.recreate(); loaded()
                compose.onNodeWithTag("settings").performClick()
                compose.onNodeWithTag("font-MONOSPACE").assertIsSelected()
                compose.onNodeWithTag("font-JETBRAINS_MONO_BOLD").performClick().assertIsSelected()
                compose.onNode(hasText("Done") and hasAnyAncestor(isDialog())).performClick()
            }
        } finally { FontSettings(context).use { it.select(previous) } }
    }
}
