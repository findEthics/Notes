package com.example.notes

import android.app.Activity
import android.app.Instrumentation
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notes.ui.notesTypography
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NoteEditorIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val helper = DatabaseHelper(context)
    private val ids = mutableListOf<Long>()
    @After fun cleanup() { helper.deleteNotes(ids); helper.close() }
    private fun editor(title: String = "", body: String = "", action: (ActivityScenario<MainActivity>, Long) -> Unit) {
        val id = helper.insertNote(title, body); ids.add(id)
        launchNote(id).use { action(it, id) }
    }
    private fun text() = compose.onNodeWithTag("note-text")
    private fun loaded() = compose.waitUntil(10000) { compose.onAllNodesWithTag("note-text").fetchSemanticsNodes().isNotEmpty() }
    private fun key(code: Int, meta: Int = 0, repeat: Int = 0) {
        compose.waitForIdle()
        val now = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, repeat, meta))
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
        instrumentation.waitForIdleSync()
    }
    private fun focusDone() {
        key(KeyEvent.KEYCODE_TAB)
        compose.onNodeWithTag("done").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("done").assertIsFocused()
        text().assertIsNotFocused()
    }
    private fun hideIme(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity {
            androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(10000) { ime(scenario) == 0 }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(200, 3000)
        instrumentation.uiAutomation.takeScreenshot().apply {
            java.io.File(context.getExternalFilesDir(null), name).outputStream().use { compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
    }
    private fun layout(): TextLayoutResult {
        val list = mutableListOf<TextLayoutResult>()
        text().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(list) }
        return list.single()
    }
    private fun assertFont(choice: FontChoice) {
        val expected = notesTypography(choice).bodyLarge
        val actual = layout().layoutInput.style
        assertEquals(expected.fontFamily, actual.fontFamily)
        assertEquals(expected.fontWeight, actual.fontWeight)
    }
    private fun ime(scenario: ActivityScenario<MainActivity>): Int {
        var bottom = 0
        scenario.onActivity { bottom = it.window.decorView.rootWindowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom }
        return bottom
    }
    @Test fun newAndExistingNotesImmediatelyFocusInputShowImeAndKeepControlsSeparate() {
        for (existing in listOf(false, true)) editor(if (existing) "Existing note" else "", if (existing) "Body" else "") { scenario, _ ->
            loaded()
            compose.waitUntil(10000) { text().fetchSemanticsNode().config[SemanticsProperties.Focused] }
            text().assertIsFocused()
            compose.waitUntil(10000) { ime(scenario) > 0 }
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("settings").assertDoesNotExist()
            compose.onNodeWithTag("create").assertDoesNotExist()
            compose.onNodeWithTag("section-panel").assertDoesNotExist()
            val input = text().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val viewport = compose.onNodeWithTag("note-viewport").fetchSemanticsNode().boundsInRoot
            val density = context.resources.displayMetrics.density
            assertEquals("Left gutter", 16f * density, input.left - viewport.left, 1f)
            assertEquals("Right gutter", 16f * density, viewport.right - input.right, 1f)
            assertTrue("Input reclaims header height", input.top <= viewport.top + 1)
            assertTrue("Usable bounded viewport", viewport.height >= 48 * density)
            val footer = compose.onNodeWithTag("note-actions").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("Text viewport extends behind actions", viewport.bottom, footer.bottom, 1f)
            assertTrue("Footer stays above IME", footer.bottom <= root.bottom - ime(scenario) + 1)
            for (tag in listOf("font-picker", "leave-input")) compose.onNodeWithTag(tag).assertDoesNotExist()
            for (tag in listOf("insert-checkbox", "share-note", "done")) {
                compose.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            }
            val share = compose.onNodeWithTag("share-note").assertContentDescriptionEquals("Share note").fetchSemanticsNode().boundsInRoot
            val done = compose.onNodeWithTag("done").assertContentDescriptionEquals("Done").fetchSemanticsNode().boundsInRoot
            val checkbox = compose.onNodeWithTag("insert-checkbox").fetchSemanticsNode().boundsInRoot
            val content = compose.onNodeWithTag("note-content").fetchSemanticsNode().boundsInRoot
            if (content.height >= 220f * density) {
                assertTrue("Share stays above Done", share.bottom <= done.top)
                assertEquals("Right-side actions align", done.right, share.right, 1f)
            } else {
                assertEquals("Compact actions stay level", done.bottom, share.bottom, 1f)
                assertTrue("Compact actions stay separate", share.right <= done.left)
            }
            assertEquals("Checkbox stays bottom-left", done.bottom, checkbox.bottom, 1f)
            assertEquals("Checkbox corner-safe gutter", 20f * density, checkbox.left - footer.left, 1f)
            assertEquals("Done corner-safe gutter", 20f * density, footer.right - done.right, 1f)
            assertEquals("IME action clearance", 8f * density, footer.bottom - done.bottom, 1f)
             assertTrue("Done stays inside footer", done.top >= footer.top && done.bottom <= footer.bottom)
              if (content.height >= 220f * density) {
                 assertTrue("Share stays inside roomy footer", share.top >= footer.top)
                  assertTrue("Roomy editor extends behind Share", viewport.bottom > share.bottom)
             }
             println("NOTE_VIEWPORT fontScale=${context.resources.configuration.fontScale} heightDp=${viewport.height / density} footerDp=${footer.height / density} contentDp=${content.height / density} imeDp=${ime(scenario) / density}")
            assertTrue("Left and right actions stay separate", checkbox.right <= done.left)
            for (bounds in listOf(share, done, checkbox)) {
                assertTrue("Action inside root", bounds.left >= root.left && bounds.right <= root.right &&
                    bounds.top >= root.top && bounds.bottom <= root.bottom)
            }
            assertEquals(if (existing) "Existing note\nBody" else "",
                text().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            if (!existing) compose.onNodeWithText("Note", useUnmergedTree = true).assertExists()
            assertEquals(20.sp, layout().layoutInput.style.fontSize)
            if (existing) assertEquals(28.sp, layout().layoutInput.text.spanStyles.first().item.fontSize)
             screenshot(if (existing) "note-existing-ime.png" else "note-new-ime.png")
             hideIme(scenario)
             compose.waitUntil(10000) {
                 compose.onNodeWithTag("share-note").fetchSemanticsNode().boundsInRoot.top >=
                     compose.onNodeWithTag("note-actions").fetchSemanticsNode().boundsInRoot.top
             }
             if (existing) screenshot("note-focused-no-ime.png")
             focusDone(); hideIme(scenario)
            screenshot("note-unfocused.png")
            val hiddenFooter = compose.onNodeWithTag("note-actions").fetchSemanticsNode().boundsInRoot
            val hiddenDone = compose.onNodeWithTag("done").fetchSemanticsNode().boundsInRoot
             val hiddenCheckbox = compose.onNodeWithTag("insert-checkbox").fetchSemanticsNode().boundsInRoot
             val hiddenShare = compose.onNodeWithTag("share-note").fetchSemanticsNode().boundsInRoot
             val hiddenViewport = compose.onNodeWithTag("note-viewport").fetchSemanticsNode().boundsInRoot
             assertTrue("Hidden IME Share stays inside footer", hiddenShare.top >= hiddenFooter.top)
              assertEquals("Hidden IME text fills content", hiddenViewport.bottom, hiddenFooter.bottom, 1f)
            assertTrue("Hidden IME leaves physical corner clearance", hiddenFooter.bottom - hiddenDone.bottom >= 16f * density - 1)
            assertEquals("Hidden IME actions stay level", hiddenDone.bottom, hiddenCheckbox.bottom, 1f)
            assertTrue("Hidden IME Done stays inside footer", hiddenDone.top >= hiddenFooter.top && hiddenDone.bottom <= hiddenFooter.bottom)
        }
    }
    @Test fun nativeUppercaseSelectionTabDpadAndEnterSpaceKeepEditingSafe() = editor { _, _ ->
        loaded(); text().performTextReplacement("normal")
        val notes = navigationMonitor(NoteScreen::class.java.name)
        val tasks = navigationMonitor(TaskScreen::class.java.name)
        try {
            key(KeyEvent.KEYCODE_N, KeyEvent.META_SHIFT_ON)
            key(KeyEvent.KEYCODE_T, KeyEvent.META_SHIFT_ON)
            compose.waitUntil(5000) { text().fetchSemanticsNode().config[SemanticsProperties.EditableText].text.contains("NT") }
            text().assertTextContains("NT", substring = true)
            key(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
            val range = text().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
            assertTrue(range.start != range.end)
            assertEquals(0, notes.hits); assertEquals(0, tasks.hits)
            key(KeyEvent.KEYCODE_TAB); compose.onNodeWithTag("insert-checkbox").assertIsFocused()
            key(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON); text().assertIsFocused()
            key(KeyEvent.KEYCODE_TAB); compose.onNodeWithTag("insert-checkbox").assertIsFocused()
            key(KeyEvent.KEYCODE_TAB); compose.onNodeWithTag("share-note").assertIsFocused()
            key(KeyEvent.KEYCODE_TAB); compose.onNodeWithTag("done").assertIsFocused()
            key(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON)
            compose.onNodeWithTag("share-note").assertIsFocused()
            key(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON)
            compose.onNodeWithTag("insert-checkbox").assertIsFocused()
            key(KeyEvent.KEYCODE_SPACE)
            text().assertIsFocused().assertTextContains("☐ ", substring = true)
            key(KeyEvent.KEYCODE_ESCAPE)
        } finally { notes.close(); tasks.close() }
    }
    @Test fun allFourOutsideInputShortcutsSaveFirstAndRejectExtraModifiersAndRepeats() {
        for (code in listOf(KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_T)) for (meta in listOf(KeyEvent.META_ALT_ON, KeyEvent.META_SHIFT_ON)) {
            editor("shortcuts-${UUID.randomUUID()}", "Before") { _, id ->
                loaded(); text().performTextReplacement("saved-$code-$meta\nBody")
                focusDone()
                val target = if (code == KeyEvent.KEYCODE_N) NoteScreen::class.java else TaskScreen::class.java
                val monitor = navigationMonitor(target.name)
                try {
                    key(code, meta, 1); assertEquals(0, monitor.hits)
                    key(code, meta or KeyEvent.META_CTRL_ON); assertEquals(0, monitor.hits)
                    key(code, meta)
                    val opened = monitor.await()
                    assertNotNull(opened)
                    assertEquals("saved-$code-$meta", helper.getNoteById(id)!!.title)
                    instrumentation.runOnMainSync { opened!!.closeEditor() }
                    instrumentation.waitForIdleSync()
                } finally { monitor.close() }
            }
        }
    }
    @Test fun longDraftScrollsCaretIntoViewWithoutMovingFooter() = editor("Long note", "body") { scenario, _ ->
        loaded(); compose.waitUntil(10000) { ime(scenario) > 0 }
        compose.waitUntil(10000) {
            compose.onNodeWithTag("note-actions").fetchSemanticsNode().boundsInRoot.bottom <=
                compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom - ime(scenario) + 1
        }
        val raw = "Title\n" + "line\n".repeat(80) + "end"
        val before = compose.onNodeWithTag("note-actions").fetchSemanticsNode().boundsInRoot
        text().performTextReplacement(raw)
        text().performSemanticsAction(SemanticsActions.SetSelection) { it(raw.length, raw.length, false) }
        compose.waitUntil(10000) {
            val node = text().fetchSemanticsNode()
            val cursor = layout().getCursorRect(raw.length)
            val viewport = compose.onNodeWithTag("note-viewport").fetchSemanticsNode().boundsInRoot
            node.positionInRoot.y + cursor.top >= viewport.top - 1 &&
                 node.positionInRoot.y + cursor.bottom <=
                     compose.onNodeWithTag("note-actions").fetchSemanticsNode().boundsInRoot.top + 1
        }
        screenshot("long-draft-caret.png")
        text().assertTextEquals(raw)
        assertEquals(before, compose.onNodeWithTag("note-actions").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("insert-checkbox").performClick()
        text().assertIsFocused().assertTextEquals(raw.removeSuffix("end") + "☐ end")
    }
    @Test fun imeCompositionCopyPasteAndSettingsShortcutGateUseActiveInput() = editor("Clipboard", "") { scenario, _ ->
        loaded()
        text().performTextClearance()
        scenario.onActivity {
            val connection = checkNotNull(it.window.decorView.findFocus().onCreateInputConnection(android.view.inputmethod.EditorInfo()))
            connection.setComposingText("draft", 1)
            connection.setComposingText("drafted", 1)
            connection.finishComposingText()
            connection.commitText("!", 1)
        }
        text().assertTextEquals("drafted!")
        key(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
        key(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
        compose.waitUntil(5000) { text().fetchSemanticsNode().config[SemanticsProperties.EditableText].text == "drafted!drafted!" }
        text().assertTextEquals("drafted!drafted!")
        val notes = navigationMonitor(NoteScreen::class.java.name)
        val tasks = navigationMonitor(TaskScreen::class.java.name)
        try {
            for (code in listOf(KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_T)) for (meta in listOf(KeyEvent.META_ALT_ON, KeyEvent.META_SHIFT_ON)) key(code, meta)
            assertEquals(0, notes.hits); assertEquals(0, tasks.hits)
            compose.onNodeWithTag("font-picker").assertDoesNotExist()
            compose.onNodeWithTag("leave-input").assertDoesNotExist()
            compose.onNodeWithTag("done").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("settings").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("settings").performClick()
            for (code in listOf(KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_T)) for (meta in listOf(KeyEvent.META_ALT_ON, KeyEvent.META_SHIFT_ON)) key(code, meta)
            assertEquals(0, notes.hits); assertEquals(0, tasks.hits)
            compose.onNode(hasText("Done") and hasAnyAncestor(isDialog())).performClick()
        } finally { notes.close(); tasks.close() }
    }

    @Test fun homeFontsApplyInEditorPersistSelectionAndDarkSurfacesAndBars() {
        val old = FontSettings(context).use { it.choice }
        val mode = context.getSystemService(UiModeManager::class.java)
        try {
            val id = helper.insertNote("Fonts", "☑ Checked\nBody"); ids.add(id)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(10000) { compose.onAllNodesWithTag("settings").fetchSemanticsNodes().isNotEmpty() }
                for (choice in FontChoice.entries) {
                    compose.onNodeWithTag("settings").performClick()
                    compose.onNodeWithTag("font-${choice.name}").performScrollTo().performClick().assertIsSelected()
                    compose.onNode(hasText("Done") and hasAnyAncestor(isDialog())).performClick()
                    assertEquals(choice, FontSettings(context).use { it.choice })
                    compose.onNodeWithTag("items").performScrollToKey("note-$id")
                    compose.onNodeWithTag("note-$id").performClick()
                    loaded()
                    compose.onNodeWithTag("font-picker").assertDoesNotExist()
                    compose.onNodeWithTag("leave-input").assertDoesNotExist()
                    compose.onNodeWithTag("insert-checkbox").assertExists()
                    compose.onNodeWithTag("done").assertExists()
                    assertFont(choice)
                    text().performSemanticsAction(SemanticsActions.SetSelection) { it(1, 3, false) }
                    scenario.recreate(); loaded()
                    compose.waitUntil(10000) {
                        text().fetchSemanticsNode().config[SemanticsProperties.Focused] && ime(scenario) > 0
                    }
                    assertEquals(choice, FontSettings(context).use { it.choice })
                    assertFont(choice)
                    assertEquals(TextRange(1, 3), text().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
                    compose.onNodeWithTag("done").performClick()
                    compose.waitUntil(10000) { compose.onAllNodesWithTag("settings").fetchSemanticsNodes().isNotEmpty() }
                }
                compose.onNodeWithTag("items").performScrollToKey("note-$id")
                compose.onNodeWithTag("note-$id").performClick()
                loaded()
                focusDone(); hideIme(scenario)
                screenshot("note-light.png")
                mode.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
                compose.waitUntil(10000) {
                    var dark = false
                    scenario.onActivity { dark = it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
                    dark
                }
                loaded()
                compose.waitUntil(10000) { layout().layoutInput.style.color == androidx.compose.ui.graphics.Color.White || layout().layoutInput.style.color.luminanceForTest() > 0.5f }
                scenario.onActivity {
                    val controller = androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView)
                    assertFalse(controller.isAppearanceLightStatusBars)
                    assertFalse(controller.isAppearanceLightNavigationBars)
                }
                focusDone(); hideIme(scenario)
                screenshot("note-dark.png")
            }
        } finally { mode.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO); FontSettings(context).use { it.select(old) } }
    }
    @Test fun nextLineCheckboxShortcutsKeepFocusAndCtrlSSavesAndCloses() = editor("Title", "Body") { _, id ->
        loaded()
        for (code in listOf(KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_O)) {
            text().performTextReplacement("Title\nBody")
            text().performSemanticsAction(SemanticsActions.SetSelection) { it(2, 2, false) }
            key(code, KeyEvent.META_CTRL_ON)
            text().assertIsFocused().assertTextEquals("Title\n☐ \nBody")
            assertEquals(TextRange(8), text().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        }
        key(KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("items").fetchSemanticsNodes().isNotEmpty() }
        text().assertDoesNotExist()
        assertEquals("☐ \nBody", helper.getNoteById(id)!!.content)
    }

    @Test fun lockStatesDoNotDisableNativeKeyboardShortcuts() {
        val locks = listOf(KeyEvent.META_CAPS_LOCK_ON, KeyEvent.META_NUM_LOCK_ON,
            KeyEvent.META_SCROLL_LOCK_ON, KeyEvent.META_CAPS_LOCK_ON or
                KeyEvent.META_NUM_LOCK_ON or KeyEvent.META_SCROLL_LOCK_ON)
        for (lock in locks) editor("Title", "Body") { _, id ->
            loaded()
            val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or lock
            for (code in listOf(KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_O)) {
                text().performTextReplacement("Title\nBody")
                text().performSemanticsAction(SemanticsActions.SetSelection) { it(2, 2, false) }
                key(code, ctrl)
                text().assertIsFocused().assertTextEquals("Title\n☐ \nBody")
            }
            assertEquals(CreationShortcut.NOTE, KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_N, 0, KeyEvent.META_ALT_ON or lock).creationShortcut(false))
            assertEquals(ListAction.ToggleDone, KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_D, 0, ctrl).listAction(false))
            assertNull(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_I, 0,
                ctrl or KeyEvent.META_SHIFT_ON).editorAction())
            key(KeyEvent.KEYCODE_S, ctrl)
            compose.waitUntil(5000) { compose.onAllNodesWithTag("items").fetchSemanticsNodes().isNotEmpty() }
            text().assertDoesNotExist()
            assertEquals("☐ \nBody", helper.getNoteById(id)!!.content)
        }
    }

    @Test fun editorScrollHintOnlyAppearsWithContentBelow() = editor("Short", "") { scenario, _ ->
        loaded(); hideIme(scenario)
        compose.onNodeWithTag("scroll-more").assertDoesNotExist()
        text().performTextReplacement("Title\n" + "line\n".repeat(80))
        text().performSemanticsAction(SemanticsActions.SetSelection) { it(0, 0, false) }
        compose.onNodeWithTag("note-viewport").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -100000f) }
        compose.onNodeWithTag("scroll-more").assertExists()
        compose.onNodeWithTag("note-viewport").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100000f) }
        compose.onNodeWithTag("scroll-more").assertDoesNotExist()
    }

    @Test fun doneSavesWithoutEditorNavigationChrome() = editor("Navigation", "Before") { scenario, id ->
        loaded(); text().performTextReplacement("Navigation\nSaved before Done")
        compose.onNodeWithTag("section-panel").assertDoesNotExist()
        compose.onNodeWithTag("create").assertDoesNotExist()
        compose.onNodeWithTag("done").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("items").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Saved before Done", helper.getNoteById(id)!!.content)
    }

    @Test fun shareSavesDraftAndSendsExactPlainTextWithOptionalSubjectWithoutClosing() = editor("Before", "body") { _, id ->
        loaded()
        val choosers = mutableListOf<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                choosers.add(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            for (raw in listOf(" Shared title \n☐ Milk\n☑ Eggs ", "\n☐ Body without title")) {
                text().performTextReplacement(raw)
                compose.onNodeWithTag("share-note").performClick()
                val chooser = choosers.last()
                val send = requireNotNull(androidx.core.content.IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))
                assertEquals(Intent.ACTION_SEND, send.action)
                assertEquals("text/plain", send.type)
                assertEquals(raw, send.getStringExtra(Intent.EXTRA_TEXT))
                val (title, body) = splitNote(raw)
                assertEquals(title.takeIf { it.isNotEmpty() }, send.getStringExtra(Intent.EXTRA_SUBJECT))
                assertEquals(title.isNotEmpty(), send.hasExtra(Intent.EXTRA_SUBJECT))
                assertEquals(title, helper.getNoteById(id)!!.title)
                assertEquals(body, helper.getNoteById(id)!!.content)
                text().assertIsDisplayed().assertTextEquals(raw)
            }
            assertEquals(2, choosers.size)
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun shareDoesNotLaunchForBlankDraftOrFailedSave() = editor("Committed", "body") { _, id ->
        loaded()
        val monitor = instrumentation.addMonitor(android.content.IntentFilter(Intent.ACTION_CHOOSER),
            Instrumentation.ActivityResult(Activity.RESULT_OK, null), true)
        val trigger = "note_share_failure"
        try {
            text().performTextReplacement(" \n\t ")
            compose.onNodeWithTag("share-note").performClick()
            assertEquals(0, monitor.hits)
            assertEquals("Committed", helper.getNoteById(id)!!.title)
            helper.writableDatabase.execSQL("CREATE TRIGGER $trigger BEFORE UPDATE ON notes WHEN OLD.id=$id BEGIN SELECT RAISE(ABORT, 'fixture write failure'); END")
            text().performTextReplacement("Unsaved\n☐ draft")
            compose.onNodeWithTag("share-note").performClick()
            assertEquals(0, monitor.hits)
            compose.onNodeWithTag("error").assertExists()
            text().assertTextEquals("Unsaved\n☐ draft")
            assertEquals("Committed", helper.getNoteById(id)!!.title)
            assertEquals("body", helper.getNoteById(id)!!.content)
        } finally {
            helper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $trigger")
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun hardwareChecklistEnterBackspaceToggleAndShiftEnter() = editor { scenario, _ ->
        // Deliver hardware events to the app: this emulator's IME converts injected Enter to commitText.
        fun hardwareKey(code: Int, meta: Int = 0) {
            compose.waitForIdle()
            scenario.onActivity {
                val now = SystemClock.uptimeMillis()
                it.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
                it.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
            }
            compose.waitForIdle()
        }
        loaded()
        text().performTextReplacement("Title\n☑ milk\nlast")
        text().performSemanticsAction(SemanticsActions.SetSelection) { it(10, 10, false) }
        hardwareKey(KeyEvent.KEYCODE_ENTER)
        text().assertTextEquals("Title\n☑ mi\n☐ lk\nlast")
        assertEquals(TextRange(13), text().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        key(KeyEvent.KEYCODE_D, KeyEvent.META_CTRL_ON)
        text().assertTextEquals("Title\n☑ mi\n☑ lk\nlast")
        key(KeyEvent.KEYCODE_D, KeyEvent.META_CTRL_ON)
        text().assertTextEquals("Title\n☑ mi\n☐ lk\nlast")
        text().performTextReplacement("☐ task")
        hardwareKey(KeyEvent.KEYCODE_ENTER, KeyEvent.META_SHIFT_ON)
        text().assertTextEquals("☐ task\n")
        key(KeyEvent.KEYCODE_D, KeyEvent.META_CTRL_ON)
        text().assertTextEquals("☐ task\n☐ ")
        hardwareKey(KeyEvent.KEYCODE_DEL)
        text().assertTextEquals("☐ task\n")
        key(KeyEvent.KEYCODE_D, KeyEvent.META_CTRL_ON)
        hardwareKey(KeyEvent.KEYCODE_ENTER)
        text().assertTextEquals("☐ task\n")
        hardwareKey(KeyEvent.KEYCODE_ENTER)
        text().assertTextEquals("☐ task\n\n")
        text().performTextReplacement("☐ task")
        hardwareKey(KeyEvent.KEYCODE_ENTER)
        text().assertTextEquals("☐ task\n☐ ")
        key(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
        text().assertTextEquals("☐ task")
    }

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Test fun stateUndoRestoresExactTextAndSelectionForEveryChecklistAction() {
        val before = EditorText("☐ task", TextRange(6))
        val state = androidx.compose.foundation.text.input.TextFieldState(before.text, before.selection)
        for (action in listOf<(EditorText) -> EditorText?>(
            { noteChecklistEnter(it) }, { insertNoteCheckbox(it, toggle = true) },
            { notePlainNewline(it) })) {
            state.editNote(action)
            state.undoState.undo()
            assertEquals(before, state.editorText())
            state.undoState.redo()
            assertNotEquals(before.text, state.text.toString())
            state.undoState.undo()
        }
        state.edit { applyEditorText(EditorText("☐ ", TextRange(2))) }
        state.undoState.clearHistory()
        state.editNote(::noteChecklistBackspace)
        state.undoState.undo()
        assertEquals(EditorText("☐ ", TextRange(2)), state.editorText())
        state.editNote { insertNoteCheckbox(it) }
        assertEquals(EditorText("☐ ", TextRange(2)), state.editorText())
        state.edit { applyEditorText(EditorText("task", TextRange(1, 3))) }
        state.undoState.clearHistory()
        state.editNote { insertNoteCheckbox(it) }
        state.undoState.undo()
        assertEquals(EditorText("task", TextRange(1, 3)), state.editorText())
    }

    @Test fun imeChecklistContinuationPasteAndUndoRedo() = editor { scenario, _ ->
        fun commit(value: String) {
            scenario.onActivity {
                val connection = checkNotNull(it.window.decorView.findFocus()
                    .onCreateInputConnection(android.view.inputmethod.EditorInfo()))
                connection.commitText(value, 1)
            }
            compose.waitForIdle()
        }
        fun undo() = key(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
        loaded(); text().performTextReplacement("☐ task")
        commit("\n"); text().assertTextEquals("☐ task\n☐ ")
        undo(); text().assertTextEquals("☐ task")
        key(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        text().assertTextEquals("☐ task\n☐ ")
        key(KeyEvent.KEYCODE_D, KeyEvent.META_CTRL_ON)
        text().assertTextEquals("☐ task\n☑ ")
        undo(); text().assertTextEquals("☐ task\n☐ ")
        key(KeyEvent.KEYCODE_DEL); text().assertTextEquals("☐ task\n")
        undo(); text().assertTextEquals("☐ task\n☐ ")
        commit("a\nb"); text().assertTextEquals("☐ task\n☐ a\nb")
        undo(); text().assertTextEquals("☐ task\n☐ ")
        commit("\n"); text().assertTextEquals("☐ task\n")
        undo(); text().assertTextEquals("☐ task\n☐ ")
    }

    @Test fun ctrlSaveEscapeAndAltEnterCloseWithSavedText() {
        for (close in listOf(KeyEvent.KEYCODE_S to KeyEvent.META_CTRL_ON,
            KeyEvent.KEYCODE_ESCAPE to 0, KeyEvent.KEYCODE_ENTER to KeyEvent.META_ALT_ON)) {
            editor("Original", "body") { scenario, id ->
                loaded(); text().performTextReplacement("Saved\nctrl")
                text().performTextReplacement("Saved\nclosed")
                key(close.first, close.second)
                compose.waitUntil(5000) { compose.onAllNodesWithTag("items").fetchSemanticsNodes().isNotEmpty() }
                assertEquals("closed", helper.getNoteById(id)!!.content)
            }
        }
    }

    @Test fun checklistEditingKeepsNativeSelectionPasteCompositionAndUndo() = editor { scenario, _ ->
        loaded(); text().performTextReplacement("☐ task")
        text().performSemanticsAction(SemanticsActions.SetSelection) { it(2, 6, false) }
        key(KeyEvent.KEYCODE_ENTER)
        text().assertTextEquals("☐ \n")
        text().performTextReplacement("☐ ")
        scenario.onActivity {
            val connection = checkNotNull(it.window.decorView.findFocus().onCreateInputConnection(android.view.inputmethod.EditorInfo()))
            connection.commitText("pasted\n☑ line", 1)
        }
        text().assertTextEquals("☐ pasted\n☑ line")
        key(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
        text().assertTextEquals("☐ ")
        scenario.onActivity {
            val connection = checkNotNull(it.window.decorView.findFocus().onCreateInputConnection(android.view.inputmethod.EditorInfo()))
            connection.setComposingText("draft", 1)
        }
        key(KeyEvent.KEYCODE_D, KeyEvent.META_CTRL_ON)
        text().assertTextEquals("☑ draft")
    }
}

private fun androidx.compose.ui.graphics.Color.luminanceForTest(): Float = (red + green + blue) / 3f
