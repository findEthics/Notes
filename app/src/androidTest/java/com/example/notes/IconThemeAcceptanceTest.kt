package com.example.notes

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Runs against real system mode set by the author on the fixture-only automation phone. */
@RunWith(AndroidJUnit4::class)
class IconThemeAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val night = InstrumentationRegistry.getArguments().getString("expectedNight") == "yes"
    private val mode = if (night) "dark" else "light"
    private val helper = DatabaseHelper(context)
    private val notes = mutableListOf<Long>()
    private val tasks = mutableListOf<Long>()
    private val directory get() = File(context.getExternalFilesDir(null), "icon-theme").apply { mkdirs() }
    private var originalFont = FontChoice.SYSTEM_DEFAULT
    @Before fun fontSetup() { FontSettings(context).use { originalFont = it.choice; it.select(FontChoice.LATO) } }
    @After fun cleanup() { FontSettings(context).use { it.select(originalFont) }; context.getSharedPreferences("notes_font_settings", 0).edit().putString(FontSettings.KEY, originalFont.displayName).commit(); helper.deleteNotes(notes); tasks.forEach { helper.deleteTask(it) }; helper.close() }
    private fun ready(tag: String) = compose.waitUntil(10000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun key(code: Int) {
        compose.waitForIdle()
        val now = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }
    private fun <T: Activity> system(scenario: ActivityScenario<T>) {
        scenario.onActivity {
            assertEquals("Activity follows actual device mode", if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
                it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            val appearance = it.window.insetsController!!.systemBarsAppearance
            assertEquals("Status icon contrast", !night, appearance and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS != 0)
            assertEquals("Navigation icon contrast", !night, appearance and WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS != 0)
            assertEquals(360, it.resources.configuration.screenWidthDp)
            println("THEME $mode ${it.javaClass.simpleName} uiMode=${it.resources.configuration.uiMode} bars=$appearance widthDp=${it.resources.configuration.screenWidthDp} fontScale=${it.resources.configuration.fontScale}")
        }
    }
    private fun shot(name: String, blackEdge: Boolean = true) {
        compose.waitForIdle(); instrumentation.uiAutomation.waitForIdle(200, 3000)
        compose.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "$mode-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (blackEdge && night) assertEquals("Actual dark app edge $name above IME", Color.BLACK, bitmap.getPixel(2, 250))
        bitmap.recycle()
        if (blackEdge && night) {
            // Inspect rendered icon ink inside the target, excluding the outer focus border.
            val tag = if (compose.onAllNodesWithTag("settings").fetchSemanticsNodes().isEmpty()) "insert-checkbox" else "settings"
            val gear = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            var visibleInk = 0
            for (y in gear.height / 4 until gear.height * 3 / 4)
                for (x in gear.width / 4 until gear.width * 3 / 4) {
                    val pixel = gear[x, y]
                    if ((pixel.red + pixel.green + pixel.blue) / 3 > 0.5f) visibleInk++
                }
            assertTrue("Editor/shell control has visible foreground ink in dark $name", visibleInk >= 10)
        }
    }
    private fun shellGeometry() {
        val panel = compose.onNodeWithTag("section-panel").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val fab = compose.onNodeWithTag("create").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val settings = compose.onNodeWithTag("settings").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("FAB clears panel", fab.bottom <= panel.top)
        assertTrue("Settings clears FAB", settings.bottom <= fab.top)
        assertTrue("Floating gear keeps a 48dp target", settings.width >= 144f && settings.height >= 144f)
        val content = compose.onNodeWithTag("items").fetchSemanticsNode().boundsInRoot
        assertTrue("List reclaims header height", content.top < settings.bottom)
        compose.onNodeWithText("Select notes").assertDoesNotExist()
    }
    private fun settings(name: String) {
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("font-LATO").assertIsSelected()
        shot("$name-settings", false)
        compose.onNode(hasText("Done") and hasAnyAncestor(isDialog())).performClick()
    }
    private fun <T: Activity> ime(scenario: ActivityScenario<T>): Int {
        var bottom = 0
        scenario.onActivity { bottom = it.window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom }
        return bottom
    }
    @Test fun actualSystemModeAllScreensAndPhoneInsets() {
        assertTrue("Use isolated fixture DB for empty acceptance", helper.getNotePreviews().isEmpty())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready("items"); compose.waitUntil(10000) { compose.onAllNodesWithText("No notes yet · Alt+N or + to create").fetchSemanticsNodes().isNotEmpty() }
            system(scenario); shellGeometry(); shot("notes-empty")
            compose.onNodeWithTag("section-TASKS").performClick()
            shot("tasks-empty"); shellGeometry()
        }
        val note = helper.insertNote("Theme acceptance", "A readable note with a checked item\n☑ Finished")
        notes.add(note)
        val task = checkNotNull(helper.insertTask("Unchecked task")); tasks.add(task)
        val checked = checkNotNull(helper.insertTask("Checked task", true)); tasks.add(checked)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready("note-$note"); system(scenario); shellGeometry(); shot("notes-populated")
            settings("notes")
            compose.onNodeWithTag("note-$note").performTouchInput { longClick() }
            compose.onNodeWithTag("delete-selected").assertIsEnabled()
            shot("notes-selection"); shellGeometry()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithTag("section-TASKS").performClick()
            ready("task-$task"); compose.onNodeWithTag("task-completed-$checked").assertIsOn()
            compose.onNodeWithTag("task-completed-$task").assertIsOff()
            shot("tasks-checked-unchecked"); shellGeometry(); settings("tasks")
        }
        launchNote(note).use { scenario ->
            ready("note-text"); compose.waitUntil(10000) { ime(scenario) > 0 }
            compose.onNodeWithTag("note-text").assertIsFocused()
            system(scenario)
            val textBounds = compose.onNodeWithTag("note-text").fetchSemanticsNode().boundsInRoot
            val viewport = compose.onNodeWithTag("note-viewport").fetchSemanticsNode().boundsInRoot
            val density = context.resources.displayMetrics.density
            println("NOTE_GEOMETRY $mode text=$textBounds viewport=$viewport density=$density")
            assertEquals("Editor left gutter", 16 * density, textBounds.left - viewport.left, 1f)
            assertEquals("Editor right gutter", 16 * density, viewport.right - textBounds.right, 1f)
            compose.onNodeWithTag("settings").assertDoesNotExist()
            compose.onNodeWithTag("create").assertDoesNotExist()
            compose.onNodeWithTag("section-panel").assertDoesNotExist()
            shot("note-editor-ime")
            val footer = compose.onNodeWithTag("note-actions").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Note footer above IME", footer.bottom <= compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom - ime(scenario) + 1)
            // Read post-animation geometry, not the viewport captured before the IME settled.
            assertEquals("Viewport extends behind floating actions", footer.bottom, compose.onNodeWithTag("note-viewport")
                .fetchSemanticsNode().boundsInRoot.bottom, 1f)
            shot("note-editor-ime-actions")
            compose.onNodeWithTag("font-picker").assertDoesNotExist()
            compose.onNodeWithTag("leave-input").assertDoesNotExist()
            key(KeyEvent.KEYCODE_TAB)
            compose.onNodeWithTag("note-text").assertIsNotFocused()
            scenario.onActivity { it.window.insetsController!!.hide(WindowInsets.Type.ime()) }
            compose.waitUntil(10000) { ime(scenario) == 0 }
            shot("note-editor-unfocused")
        }
        launchTask(task).use { scenario ->
            ready("task-text"); system(scenario)
            compose.onNodeWithTag("section-TASKS").assertIsSelected()
            compose.onNodeWithTag("items").assertIsDisplayed()
            compose.onNodeWithTag("task-$checked").assertIsDisplayed()
            shot("task-editor")
            val fields = compose.onNodeWithTag("task-fields").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("settings").assertIsDisplayed()
            compose.onNodeWithTag("task-actions").assertDoesNotExist()
            compose.onNodeWithTag("font-picker").assertDoesNotExist()
            compose.onNodeWithTag("leave-input").assertDoesNotExist()
            compose.onNodeWithTag("parent-completed").performClick().assertIsOn()
            shot("task-editor-toggled")
            compose.onNodeWithTag("task-text").performClick()
            compose.waitUntil(10000) { ime(scenario) > 0 }
            shot("task-editor-ime")
            compose.onNodeWithTag("task-text").assertIsFocused()
            compose.onNodeWithTag("done").assertIsDisplayed()
            val input = compose.onNodeWithTag("task-text").fetchSemanticsNode().boundsInRoot
            val done = compose.onNodeWithTag("done").fetchSemanticsNode().boundsInRoot
            assertTrue("Task input clears inline Done", input.right <= done.left)
            val currentFields = compose.onNodeWithTag("task-fields").fetchSemanticsNode().boundsInRoot
            assertTrue("Done stays in task row", done.top >= currentFields.top && done.bottom <= currentFields.bottom)
            assertTrue("Task Done above IME", done.bottom <= compose.onNode(isRoot() and hasAnyDescendant(hasTestTag("task-fields"))).fetchSemanticsNode().boundsInRoot.bottom - ime(scenario) + 1)
            println("GEOMETRY $mode fields=$fields input=$input done=$done ime=${ime(scenario)}")
            key(KeyEvent.KEYCODE_TAB)
            compose.onNodeWithTag("task-text").assertIsNotFocused()
            compose.waitUntil(10000) { ime(scenario) == 0 }
            shot("task-editor-unfocused")
            settings("task-editor")
        }
        launchTask(Long.MAX_VALUE).use { scenario ->
            ready("error"); system(scenario); compose.onNodeWithText("Retry").assertIsDisplayed()
            shot("task-load-error")
        }
        // UI removal is immediate; persistence waits until Undo expires or the list stops.
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SECTION, 0)).use {
            ready("note-$note"); compose.onNodeWithTag("note-$note").performTouchInput { longClick() }
            compose.onNodeWithTag("delete-selected").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("note-$note").fetchSemanticsNodes().isEmpty() }
            shot("notes-deleted"); assertNotNull(helper.getNoteById(note))
            compose.onNodeWithContentDescription("Dismiss").performClick()
            compose.waitUntil(10000) { helper.getNoteById(note) == null }
        }
    }
    @Test fun manifestAdaptiveAndMonochromeResolveAcrossDensities() {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals(R.mipmap.notes_launcher, info.icon)
        for ((label, id) in listOf("normal" to R.mipmap.notes_launcher, "round" to R.mipmap.notes_launcher_round)) {
            for (density in listOf(160, 240, 320, 480, 640)) {
                val icon = context.resources.getDrawableForDensity(id, density, context.theme)
                assertTrue("$label/$density is adaptive, never a legacy bitmap", icon is AdaptiveIconDrawable)
                icon as AdaptiveIconDrawable
                val size = 48 * density / 160
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                icon.setBounds(0, 0, size, size); icon.draw(Canvas(bitmap))
                File(directory, "icon-$label-$density.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                if (Build.VERSION.SDK_INT >= 33) {
                    val mono = checkNotNull(icon.monochrome)
                    val palette = com.example.notes.ui.notesColorScheme(context, night)
                    val preview = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(preview)
                    canvas.clipPath(icon.iconMask)
                    canvas.drawColor(palette.primaryContainer.toArgb())
                    mono.setTint(palette.onPrimaryContainer.toArgb())
                    // Match adaptive drawable's 1/4 extra inset for its 108-unit layer.
                    val inset = size / 4
                    mono.setBounds(-inset, -inset, size + inset, size + inset); mono.draw(canvas)
                    File(directory, "$mode-icon-monochrome-$label-$density.png").outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    preview.recycle()
                    println("ICON $label density=$density adaptive=true monochrome=true dynamicPrimary=${palette.primary}")
                }
            }
        }
    }
}
