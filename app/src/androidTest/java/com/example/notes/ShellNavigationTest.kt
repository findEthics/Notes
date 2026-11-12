package com.example.notes

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun key(code: Int, meta: Int = 0) {
        compose.waitForIdle()
        val now = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now,now,KeyEvent.ACTION_DOWN,code,0,meta))
        instrumentation.sendKeySync(KeyEvent(now,now,KeyEvent.ACTION_UP,code,0,meta))
        instrumentation.waitForIdleSync()
    }
    @Test fun selectedSectionFabAndKeyboardShortcutOpenTasksWithInputFocused() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithTag("section-TASKS").performClick().assertIsSelected()
            scenario.recreate()
            compose.onNodeWithTag("section-TASKS").assertIsSelected()
            val monitor = navigationMonitor(TaskScreen::class.java.name)
            compose.onNodeWithTag("create").performClick()
            val editor = monitor.await()
            assertNotNull(editor)
            compose.onNodeWithTag("task-text").assertIsFocused()
            key(KeyEvent.KEYCODE_TAB)
            compose.onNodeWithTag("task-text").assertIsNotFocused()
            compose.onNodeWithTag("done").assertIsFocused()
            key(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON)
            compose.onNodeWithTag("task-text").assertIsFocused()
            key(KeyEvent.KEYCODE_TAB)
            compose.onNodeWithTag("task-text").assertIsNotFocused()
            compose.onNodeWithTag("done").assertIsFocused()
            compose.onNodeWithTag("items").assertIsDisplayed()
            compose.onNodeWithTag("section-TASKS").assertIsSelected()
            key(KeyEvent.KEYCODE_SPACE)
            compose.onNodeWithTag("task-text").assertDoesNotExist()
            instrumentation.waitForIdleSync()
            compose.onNodeWithTag("section-NOTES").performClick()
            // A new inline editor has no navigation destination event.
            monitor.reset()
            key(KeyEvent.KEYCODE_T,KeyEvent.META_ALT_ON)
            val next = monitor.await()
            assertNotNull(next)
            key(KeyEvent.KEYCODE_ESCAPE)
            instrumentation.waitForIdleSync()
            compose.onNodeWithTag("section-TASKS").assertIsSelected()
            monitor.close()
        }
    }
    @Test fun exactFourCreationShortcutsWorkFromShellAndUnfocusedTaskAndSaveFirst() {
        val context = instrumentation.targetContext
        DatabaseHelper(context).use { helper ->
            val id = helper.insertTask("shortcut matrix")!!
            try {
                for (inEditor in listOf(false, true)) for (code in listOf(KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_T))
                    for (meta in listOf(KeyEvent.META_ALT_ON, KeyEvent.META_SHIFT_ON)) {
                        (if (inEditor) launchTask(id) else ActivityScenario.launch(MainActivity::class.java)).use {
                            if (inEditor) {
                                compose.waitUntil(10000) { compose.onAllNodesWithTag("task-text").fetchSemanticsNodes().isNotEmpty() }
                                compose.onNodeWithTag("task-text").performClick().performTextReplacement("saved-$code-$meta")
                                compose.waitForIdle()
                                 key(KeyEvent.KEYCODE_TAB)
                                compose.onNodeWithTag("task-text").assertIsNotFocused()
                                compose.onNodeWithTag("done").assertIsFocused()
                            }
                            val taskTarget = code == KeyEvent.KEYCODE_T || (inEditor && meta == KeyEvent.META_ALT_ON)
                            val target = if (taskTarget) TaskScreen::class.java else NoteScreen::class.java
                            val monitor = navigationMonitor(target.name)
                            key(code, meta)
                            val opened = monitor.await()
                            assertNotNull("code=$code meta=$meta editor=$inEditor", opened)
                            if (inEditor) assertEquals("saved-$code-$meta", helper.getTaskById(id)!!.text)
                            instrumentation.runOnMainSync { opened!!.closeEditor() }
                            instrumentation.waitForIdleSync()
                            monitor.close()
                        }
                    }
            } finally { helper.deleteTask(id) }
        }
    }

    @Test fun altNUsesSelectedSectionAndAltTStillCreatesInlineTask() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithTag("section-NOTES").performClick()
            key(KeyEvent.KEYCODE_N, KeyEvent.META_ALT_ON)
            compose.waitUntil(10000) { compose.onAllNodesWithTag("note-text").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { assertEquals("note/{id}", it.navController.currentDestination?.route) }
            key(KeyEvent.KEYCODE_ESCAPE)
            compose.onNodeWithTag("section-TASKS").performClick()
            for (code in listOf(KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_T)) {
                key(code, KeyEvent.META_ALT_ON)
                compose.onNodeWithTag("task-text").assertIsDisplayed().assertIsFocused()
                scenario.onActivity { assertEquals("list", it.navController.currentDestination?.route) }
                key(KeyEvent.KEYCODE_ESCAPE)
                compose.onNodeWithTag("task-text").assertDoesNotExist()
                compose.onNodeWithTag("section-TASKS").assertIsSelected()
            }
            key(KeyEvent.KEYCODE_N, KeyEvent.META_SHIFT_ON)
            compose.waitUntil(10000) { compose.onAllNodesWithTag("note-text").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { assertEquals("note/{id}", it.navController.currentDestination?.route) }
            key(KeyEvent.KEYCODE_ESCAPE)
        }
    }

    @Test fun shellFontChoicePropagatesToTaskSettingsAndPersistsAcrossResume() {
        val context = instrumentation.targetContext
        val old = FontSettings(context).use { it.choice }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithTag("settings").performClick()
                compose.onNodeWithTag("font-JETBRAINS_MONO_SEMIBOLD").performClick().assertIsSelected()
                compose.onNode(hasText("Done") and hasAnyAncestor(isDialog())).performClick()
                compose.onNodeWithTag("section-TASKS").performClick()
                val monitor = navigationMonitor(TaskScreen::class.java.name)
                compose.onNodeWithTag("create").performClick()
                val editor = monitor.await()
                assertNotNull(editor)
                compose.onNodeWithTag("settings").performClick()
                compose.onNodeWithTag("font-JETBRAINS_MONO_SEMIBOLD").assertIsSelected()
                compose.onNodeWithTag("font-SYSTEM_DEFAULT").performClick()
                compose.onNode(hasText("Done") and hasAnyAncestor(isDialog())).performClick()
                instrumentation.runOnMainSync { editor!!.closeEditor() }
                instrumentation.waitForIdleSync()
                compose.onNodeWithTag("settings").performClick()
                compose.onNodeWithTag("font-SYSTEM_DEFAULT").assertIsSelected()
                monitor.close()
            }
        } finally { FontSettings(context).use { it.select(old) } }
    }
}
