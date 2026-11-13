@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.notes

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextDecoration

/** Plain-text storage contract; spans never change characters or offsets. */
fun splitNote(text: String): Pair<String, String> {
    val end = text.indexOf('\n')
    if (end < 0) return text.trim() to ""
    val title = text.substring(0, end).trim()
    val rest = text.substring(end + 1)
    val body = rest.trim()
    return title to if (title.isNotEmpty() && body.isNotEmpty() && Regex("^[ \\t]*\\n").containsMatchIn(rest)) "\n$body" else body
}

fun restoredNoteText(title: String, content: String): String =
    (if (content.isNotEmpty()) "$title\n$content" else title).split('\n').joinToString("\n") {
        if (it == "☐" || it == "☑") "$it " else it
    }

data class EditorText(val text: String = "", val selection: TextRange = TextRange.Zero)

/** Map both endpoints through the trim and literal-checkbox reconstruction used on load. */
fun normalizedNoteSelection(value: EditorText): TextRange {
    val text = value.text
    val newline = text.indexOf('\n')
    val titleEnd = if (newline < 0) text.length else newline
    fun trimmedRange(start: Int, end: Int): IntRange {
        var first = start; var last = end
        while (first < last && text[first].isWhitespace()) first++
        while (last > first && text[last - 1].isWhitespace()) last--
        return first until last
    }
    val title = trimmedRange(0, titleEnd)
    val body = trimmedRange(if (newline < 0) text.length else newline + 1, text.length)
    val titleLength = title.count()
    val bodyLength = body.count()
    val (savedTitle, savedBody) = splitNote(text)
    val base = if (savedBody.isEmpty()) savedTitle else "$savedTitle\n$savedBody"
    fun offset(raw: Int): Int {
        val position = raw.coerceIn(0, text.length)
        val normalized = if (bodyLength > 0 && position > newline)
            titleLength + 1 + (if (savedBody.startsWith('\n') && position > newline + 1) 1 else 0) +
                (position - body.first).coerceIn(0, bodyLength)
        else (position - title.first).coerceIn(0, titleLength)
        var inserted = 0
        var lineStart = 0
        base.split('\n').forEach { line ->
            if ((line == "☐" || line == "☑") && normalized > lineStart) inserted++
            lineStart += line.length + 1
        }
        return normalized + inserted
    }
    return TextRange(offset(value.selection.start), offset(value.selection.end))
}

private fun noteLineStart(value: EditorText): Int =
    value.text.lastIndexOf('\n', value.selection.end - 1) + 1

internal fun String.hasNoteCheckbox(start: Int): Boolean =
    startsWith("☐ ", start) || startsWith("☑ ", start)

/** Toolbar insertion is idempotent; Ctrl+D also toggles an existing marker. */
fun insertNoteCheckbox(value: EditorText, toggle: Boolean = false): EditorText {
    val start = noteLineStart(value)
    if (value.text.hasNoteCheckbox(start)) {
        return if (toggle) value.copy(text = value.text.replaceRange(start, start + 1,
            if (value.text[start] == '☐') "☑" else "☐")) else value
    }
    fun shifted(range: TextRange) = TextRange(
        range.start + if (range.start >= start) 2 else 0,
        range.end + if (range.end >= start) 2 else 0)
    return value.copy(text = value.text.replaceRange(start, start, "☐ "),
        selection = shifted(value.selection))
}

/** Null leaves normal editing (including ranges and IME composition) to the text field. */
fun noteChecklistEnter(value: EditorText, shift: Boolean = false): EditorText? {
    if (shift || !value.selection.collapsed) return null
    val start = noteLineStart(value)
    val caret = value.selection.end
    if (!value.text.hasNoteCheckbox(start) || caret < start + 2) return null
    val end = value.text.indexOf('\n', start).let { if (it < 0) value.text.length else it }
    return if (value.text.substring(start + 2, end).isBlank())
        value.copy(text = value.text.removeRange(start, end), selection = TextRange(start))
    else value.copy(text = value.text.replaceRange(caret, caret, "\n☐ "), selection = TextRange(caret + 3))
}

fun noteChecklistBackspace(value: EditorText): EditorText? {
    if (!value.selection.collapsed) return null
    val start = noteLineStart(value)
    val end = value.text.indexOf('\n', start).let { if (it < 0) value.text.length else it }
    if (!value.text.hasNoteCheckbox(start) || value.selection.end != start + 2 ||
        !value.text.substring(start + 2, end).isBlank()) return null
    return value.copy(text = value.text.removeRange(start, start + 2), selection = TextRange(start))
}

fun notePlainNewline(value: EditorText): EditorText = EditorText(
    value.text.replaceRange(value.selection.min, value.selection.max, "\n"), TextRange(value.selection.min + 1))

fun styledNoteText(text: AnnotatedString, titleStyle: SpanStyle): AnnotatedString {
        val styled = AnnotatedString.Builder(text.text)
        val titleEnd = text.text.indexOf('\n').let { if (it < 0) text.length else it }
        if (titleEnd > 0) styled.addStyle(titleStyle, 0, titleEnd)
        var start = 0
        text.text.split('\n').forEach { line ->
            if (line.startsWith("☑ ") && line.trim() != "☑")
                styled.addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start + 2, start + line.length)
            start += line.length + 1
        }
        return styled.toAnnotatedString()
}

fun noteOutputTransformation(titleStyle: SpanStyle) = OutputTransformation {
    styledNoteText(AnnotatedString(asCharSequence().toString()), titleStyle).spanStyles.forEach {
        addStyle(it.item, it.start, it.end)
    }
}

fun TextFieldState.editorText() = EditorText(text.toString(), selection)

fun TextFieldBuffer.applyEditorText(value: EditorText) {
    replace(0, length, value.text)
    selection = value.selection
}

fun TextFieldState.editNote(transform: (EditorText) -> EditorText?) {
    val next = transform(editorText()) ?: return
    edit { applyEditorText(next) }
}

/** Match only a single newline inserted at a collapsed caret, never a multiline paste/range replacement. */
fun TextFieldBuffer.isCaretNewline(): Boolean {
    if (!originalSelection.collapsed || changes.changeCount != 1) return false
    val before = changes.getOriginalRange(0)
    val after = changes.getRange(0)
    return before == originalSelection && after.length == 1 &&
        asCharSequence()[after.min] == '\n'
}

val NoteInputTransformation = InputTransformation {
    val before = EditorText(originalText.toString(), originalSelection)
    val next = if (isCaretNewline()) noteChecklistEnter(before)
    else if (changes.changeCount == 1 && originalSelection.collapsed && originalSelection.end > 0 &&
        changes.getRange(0).collapsed &&
        changes.getOriginalRange(0) == TextRange(originalSelection.end - 1, originalSelection.end))
        noteChecklistBackspace(before)
    else null
    if (next != null) applyEditorText(next)
}

/** Movement is sticky; a return to the down point cannot turn a drag into a tap. */
class NoteCheckboxTap(private val x: Float, private val y: Float, private val time: Long,
    private val slop: Float, private val timeout: Long) {
    private var excluded = false
    fun move(x: Float, y: Float, singlePointer: Boolean = true) {
        val dx = x - this.x; val dy = y - this.y
        if (!singlePointer || dx * dx + dy * dy > slop * slop) excluded = true
    }
    fun cancel() { excluded = true }
    fun finish(x: Float, y: Float, time: Long): Boolean {
        move(x, y)
        return !excluded && time - this.time in 0 until timeout
    }
}
