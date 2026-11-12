package com.example.notes

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
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
            key(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON)
            compose.onNodeWithTag("task-text").assertIsFocused()
            compose.onNodeWithTag("leave-input").performClick()
            compose.waitUntil(10000) {
                !compose.onNodeWithTag("task-text").fetchSemanticsNode().config[SemanticsProperties.Focused]
            }
            compose.onNodeWithTag("task-text").assertIsNotFocused()
            compose.onNodeWithTag("done").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            compose.onNodeWithTag("task-menu").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithTag("done").assertIsFocused()
            key(KeyEvent.KEYCODE_SPACE)
            instrumentation.waitForIdleSync()
            compose.onNodeWithTag("section-NOTES").performClick()
            // waitForMonitorWithTimeout removes the monitor after the first match.
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
                                 compose.onNodeWithTag("leave-input").performClick()
                                compose.onNodeWithTag("task-text").assertIsNotFocused()
                                compose.onNodeWithTag("done").assertIsFocused()
                            }
                            val target = if (code == KeyEvent.KEYCODE_N) NoteScreen::class.java else TaskScreen::class.java
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
                compose.onNodeWithTag("font-picker").performClick()
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
