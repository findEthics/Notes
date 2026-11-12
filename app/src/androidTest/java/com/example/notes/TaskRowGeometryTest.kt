package com.example.notes

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.notes.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskRowGeometryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun firstLineAlignmentAndStrikeAcrossFontsScalingAndMultilineEditors() {
        var choice by mutableStateOf(FontChoice.SYSTEM_DEFAULT)
        var scale by mutableStateOf(1f)
        var editor by mutableStateOf(false)
        var checked by mutableStateOf(false)
        var baseline = 0f
        var delta = 0f
        var provided = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                NotesTheme(fontChoice = choice) {
                    val style = taskLabelStyle(checked)
                    val metrics = rememberTextMeasurer().measure("M", style)
                    delta = metrics.firstBaseline - (metrics.getLineTop(0) + metrics.getLineBottom(0)) / 2
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        TaskCheckRow(Modifier.fillMaxWidth(), checkbox = { alignment ->
                            Checkbox(checked, { checked = it }, alignment.testTag("check"))
                        }) { alignment ->
                            val label = alignment.testTag("label").onGloballyPositioned {
                                val line = if (editor) it[TaskInputBaseline] else it[FirstBaseline]
                                provided = line != AlignmentLine.Unspecified
                                baseline = it.positionInRoot().y + line
                            }
                             if (editor) TaskEditorInput(androidx.compose.foundation.text.input.rememberTextFieldState("First line\nSecond line\nThird line"),
                                 "Task text", checked, modifier = label)
                             else Text("First line\nSecond line\nThird line", modifier = label, style = style)
                             if (editor) TextButton(onClick = {}, modifier = Modifier.heightIn(min = 48.dp).testTag("done")) {
                                 Text("Done")
                             }
                        }
                    }
                }
            }
        }
        for (font in FontChoice.entries) for (fontScale in listOf(1f, 2f)) for (input in listOf(false, true)) {
            compose.runOnIdle { choice = font; scale = fontScale; editor = input; checked = false }
            compose.waitForIdle()
            assertTrue("first baseline exported: $font scale=$fontScale editor=$input", provided)
            val check = compose.onNodeWithTag("check")
            check.assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).assertIsOff()
            val center = check.fetchSemanticsNode().boundsInRoot.center.y
            assertEquals("first-line center: $font scale=$fontScale editor=$input", baseline - delta, center, 2f)
            val rect = compose.onNodeWithTag("label").fetchSemanticsNode().boundsInRoot
            assertTrue("label beside checkbox", rect.left >= check.fetchSemanticsNode().boundsInRoot.right)
            if (input) {
                val done = compose.onNodeWithTag("done").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                    .fetchSemanticsNode().boundsInRoot
                assertTrue("inline input clears Done: $font scale=$fontScale", rect.right <= done.left)
            }
            check.performClick().assertIsOn()
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("label").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(results) }
            assertEquals(androidx.compose.ui.text.style.TextDecoration.LineThrough, results.single().layoutInput.style.textDecoration)
            check.performClick().assertIsOff()
        }
    }
}
