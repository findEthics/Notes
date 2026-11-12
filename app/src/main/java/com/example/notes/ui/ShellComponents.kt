package com.example.notes.ui

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.ui.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.example.notes.FontChoice
import com.example.notes.FontSettings
import com.example.notes.R

enum class Section { NOTES, TASKS }

@Composable
fun Modifier.focusRing(): Modifier {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    return onFocusChanged { focused = it.isFocused }.border(1.dp,
        if (focused && keyboard) MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.small)
}

@Composable
fun BottomSectionPanel(section: Section, onSection: (Section) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().testTag("section-panel").padding(horizontal = 16.dp, vertical = 4.dp)) {
            Section.entries.forEach { item ->
                TextButton(onClick = { onSection(item) }, modifier = Modifier.weight(1f)
                    .heightIn(min = 48.dp).focusRing().testTag("section-${item.name}").semantics {
                        selected = section == item; role = Role.Tab
                    },
                     colors = ButtonDefaults.textButtonColors(
                        containerColor = if (section == item) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        contentColor = if (section == item) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)) {
                     Text(stringResource(if (item == Section.NOTES) R.string.notes_section else R.string.tasks_section),
                        style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
fun FontSettingsDialog(settings: FontSettings, onKeyboardShortcuts: (() -> Unit)? = null, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge, title = { Text(stringResource(R.string.font_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (onKeyboardShortcuts != null) TextButton(onClick = onKeyboardShortcuts,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRing().testTag("keyboard-shortcuts")) {
                    Text("Keyboard shortcuts")
                }
                FontChoice.entries.forEach { choice ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRing()
                        .testTag("font-${choice.name}")
                        .selectable(settings.choice == choice, role = Role.RadioButton,
                            onClick = { settings.select(choice) }).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = settings.choice == choice, onClick = null)
                        Text(choice.displayName, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).focusRing()) {
            Text(stringResource(R.string.done))
        } })
}

@Composable
fun ErrorNotice(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp).padding(end = 48.dp).testTag("error")) {
        Text(message, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry, modifier = Modifier.focusRing()) { Text(stringResource(R.string.retry)) }
    }
}

/** Overlay only: caller content starts at its normal safe inset, without a reserved header. */
@Composable
fun TopRightFontSettings(onClick: () -> Unit) {
    val label = stringResource(R.string.font_settings)
    Box(Modifier.fillMaxSize().windowInsetsPadding(
        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        IconButton(onClick = onClick, modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd)
            .size(48.dp).focusRing().testTag("settings").semantics {
                contentDescription = label
            }) { Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_font_settings),
                contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp)) }
    }
}

@Composable
fun taskLabelStyle(completed: Boolean) = MaterialTheme.typography.bodyLarge.copy(
    textDecoration = if (completed)
        androidx.compose.ui.text.style.TextDecoration.LineThrough else androidx.compose.ui.text.style.TextDecoration.None)

/** Align the checkbox center with the first line, using selected-font metrics, never row height. */
@Composable
fun TaskCheckRow(
    modifier: Modifier = Modifier,
    checkbox: @Composable (Modifier) -> Unit,
    content: @Composable RowScope.(Modifier) -> Unit
) {
    val metrics = androidx.compose.ui.text.rememberTextMeasurer().measure("M", taskLabelStyle(false))
    val baselineFromCenter = (metrics.firstBaseline -
        (metrics.getLineTop(0) + metrics.getLineBottom(0)) / 2).toInt()
    Row(modifier.heightIn(min = 48.dp)) {
        checkbox(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .alignBy { it.measuredHeight / 2 + baselineFromCenter })
        content(Modifier.weight(1f).alignBy {
            it[TaskInputBaseline].takeUnless { line -> line == AlignmentLine.Unspecified } ?: it[FirstBaseline]
        })
    }
}

// Export the editable text baseline through its padded container.
val TaskInputBaseline = HorizontalAlignmentLine { old, new -> minOf(old, new) }

@Composable
fun TaskEditorInput(state: TextFieldState, label: String,
    completed: Boolean, modifier: Modifier = Modifier, inputTransformation: InputTransformation? = null) {
    val style = taskLabelStyle(completed)
    BasicTextField(state = state, inputTransformation = inputTransformation,
        textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier.focusRing().semantics { contentDescription = label }.heightIn(min = 48.dp),
        decorator = { inner ->
            Box(Modifier.padding(vertical = 8.dp)) {
                if (state.text.isEmpty()) Text(label, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.layout { measurable, constraints ->
                         val input = measurable.measure(constraints)
                         layout(input.width, input.height, mapOf(TaskInputBaseline to input[FirstBaseline])) {
                             input.placeRelative(0, 0)
                         }
                     }) { inner() }
            }
        })
}

@Composable
fun EditorFontPicker(onClick: () -> Unit) {
    val label = stringResource(R.string.font_settings)
    IconButton(onClick, Modifier.size(48.dp).focusRing().testTag("font-picker")
        .semantics { contentDescription = label }) { Text("Aa", style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun LeaveInputButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(48.dp).focusRing().testTag("leave-input"), enabled = enabled) {
        Icon(painterResource(R.drawable.ic_hide_keyboard), stringResource(R.string.leave_input))
    }
}

@Composable
fun RowActions(tag: String, label: String, actionLabel: String, actionTag: String,
    enabled: Boolean = true, onAction: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton({ expanded = true }, Modifier.size(48.dp).focusRing().testTag(tag), enabled = enabled) {
            Icon(painterResource(R.drawable.ic_more_vert), label)
        }
        DropdownMenu(expanded, { expanded = false }, shape = MaterialTheme.shapes.extraSmall,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            DropdownMenuItem(text = { Text(actionLabel) }, onClick = { expanded = false; onAction() },
                modifier = Modifier.heightIn(min = 48.dp).focusRing().testTag(actionTag))
        }
    }
}
