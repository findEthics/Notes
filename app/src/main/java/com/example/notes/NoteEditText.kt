package com.example.notes

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.TextWatcher
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatEditText

class NoteEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    companion object {
        private const val CHECKBOX_UNCHECKED = "☐ "
        private const val CHECKBOX_CHECKED = "☑ "
    }

    private var ignoreTextChange = false
    private var lastMovementTime = 0L

    init {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // If Enter key pressed, potentially add a checkbox
                if (count == 1 && start > 0 && s != null && s[start] == '\n') {
                    post {
                        val cursorPosition = selectionStart
                        val editable = text
                        if (editable != null && cursorPosition > 0) {
                            editable.insert(cursorPosition, CHECKBOX_UNCHECKED)
                        }
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) {
                if (ignoreTextChange || s == null) return

                ignoreTextChange = true

                // Format the first line as bold (title)
                formatTitle(s)

                // Apply strikethrough to checked items
                applyStrikethroughToCheckedItems(s)

                ignoreTextChange = false
            }
        })
    }

    private fun formatTitle(editable: Editable) {
        val text = editable.toString()
        val firstLineEnd = text.indexOf('\n')

        if (firstLineEnd > 0) {
            // Clear any existing spans in the first line
            val spans = editable.getSpans(0, firstLineEnd, StyleSpan::class.java)
            for (span in spans) {
                editable.removeSpan(span)
            }

            // Apply bold style to the first line
            editable.setSpan(StyleSpan(Typeface.BOLD), 0, firstLineEnd, Editable.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (text.isNotEmpty()) {
            // If there's no newline yet, apply bold to the entire text
            val spans = editable.getSpans(0, text.length, StyleSpan::class.java)
            for (span in spans) {
                editable.removeSpan(span)
            }

            editable.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Editable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun applyStrikethroughToCheckedItems(editable: Editable) {
        val text = editable.toString()
        val lines = text.split('\n')

        var position = 0
        for (line in lines) {
            if (line.startsWith(CHECKBOX_CHECKED)) {
                val lineStart = position + CHECKBOX_CHECKED.length
                val lineEnd = position + line.length

                // Remove existing strikethrough spans
                val spans = editable.getSpans(lineStart, lineEnd, StrikethroughSpan::class.java)
                for (span in spans) {
                    editable.removeSpan(span)
                }

                // Apply strikethrough
                editable.setSpan(StrikethroughSpan(), lineStart, lineEnd, Editable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (line.startsWith(CHECKBOX_UNCHECKED)) {
                val lineStart = position + CHECKBOX_UNCHECKED.length
                val lineEnd = position + line.length

                // Remove any strikethrough spans
                val spans = editable.getSpans(lineStart, lineEnd, StrikethroughSpan::class.java)
                for (span in spans) {
                    editable.removeSpan(span)
                }
            }
            position += line.length + 1 // +1 for the newline
        }
    }

    fun insertCheckbox() {
        val selStart = selectionStart
        val editable = text ?: return

        editable.insert(selStart, CHECKBOX_UNCHECKED)
    }

//    override fun onTouchEvent(event: MotionEvent): Boolean {
//        if (event.action == MotionEvent.ACTION_UP) {
//            val offset = getOffsetForPosition(event.x, event.y)
//            val line = layout.getLineForOffset(offset)
//            val lineStart = layout.getLineStart(line)
//            val lineEnd = layout.getLineEnd(line)
//
//            val text = text?.subSequence(lineStart, lineEnd).toString()
//            if (text.startsWith(CHECKBOX_UNCHECKED) || text.startsWith(CHECKBOX_CHECKED)) {
//                toggleCheckbox(lineStart, lineEnd)
//                moveCheckedToBottom(lineStart, lineEnd)
//                return true // Consume the touch event
//            }
//        }
//        return super.onTouchEvent(event)
//    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val offset = getOffsetForPosition(event.x, event.y)
            val line = layout.getLineForOffset(offset)
            val lineStart = layout.getLineStart(line)
            val lineEnd = layout.getLineEnd(line)
            val text = text?.subSequence(lineStart, lineEnd).toString()
            if (text.startsWith(CHECKBOX_UNCHECKED) || text.startsWith(CHECKBOX_CHECKED)) {
                toggleCheckbox(lineStart, lineEnd)
                handleTaskMovement(lineStart, lineEnd)
                return true // Consume the touch event
            }
        }
        return super.onTouchEvent(event)
    }

    private fun toggleCheckbox(lineStart: Int, lineEnd: Int) {
        val editable = text ?: return
        val lineText = editable.subSequence(lineStart, lineEnd).toString()

        editable.replace(lineStart, lineEnd, when {
            lineText.startsWith(CHECKBOX_UNCHECKED) ->
                CHECKBOX_CHECKED + lineText.substring(2)
            lineText.startsWith(CHECKBOX_CHECKED) ->
                CHECKBOX_UNCHECKED + lineText.substring(2)
            else -> lineText
        })
    }

    private fun moveCheckedToBottom(lineStart: Int, lineEnd: Int) {
        val editable = text ?: return
        val lineText = editable.subSequence(lineStart, lineEnd)
        val lineEndWithNewline = if (editable.getOrNull(lineEnd) == '\n') lineEnd + 1 else lineEnd

        editable.delete(lineStart, lineEndWithNewline)
        editable.append("\n$lineText")

        // Update spans and formatting
        post {
            ignoreTextChange = true
            applyStrikethroughToCheckedItems(editable)
            formatTitle(editable)
            ignoreTextChange = false
        }
    }

    private fun moveTaskBasedOnState(lineStart: Int, lineEnd: Int) {
        val editable = text ?: return
        val lineText = editable.subSequence(lineStart, lineEnd).toString()
        val lineEndWithNewline = if (editable.getOrNull(lineEnd) == '\n') lineEnd + 1 else lineEnd

        // Delete the current line
        editable.delete(lineStart, lineEndWithNewline)

        // Check if the task is checked or unchecked and move accordingly
        if (lineText.startsWith(CHECKBOX_CHECKED)) {
            // If checked, move to bottom
            if (editable.isNotEmpty() && editable.last() != '\n') {
                editable.append("\n")
            }
            editable.append(lineText)
        } else if (lineText.startsWith(CHECKBOX_UNCHECKED)) {
            // If unchecked, move to top (right after the title)
            val firstLineEnd = editable.toString().indexOf('\n')
            if (firstLineEnd > 0) {
                // There is a title, insert after it
                editable.insert(firstLineEnd + 1, "$lineText\n")
            } else {
                // No title or empty document
                if (editable.isNotEmpty()) {
                    // Insert at beginning with a newline after
                    editable.insert(0, "$lineText\n")
                } else {
                    // Empty document
                    editable.append(lineText)
                }
            }
        }

        // Update spans and formatting
        post {
            ignoreTextChange = true
            applyStrikethroughToCheckedItems(editable)
            formatTitle(editable)
            ignoreTextChange = false
        }
    }

    private fun handleTaskMovement(lineStart: Int, lineEnd: Int) {
        if (System.currentTimeMillis() - lastMovementTime < 100) return
        lastMovementTime = System.currentTimeMillis()
        val editable = text ?: return
        val lineText = editable.subSequence(lineStart, lineEnd).toString()
        val lineEndWithNewline = if (editable.getOrNull(lineEnd) == '\n') lineEnd + 1 else lineEnd

        editable.delete(lineStart, lineEndWithNewline)

        when {
            lineText.startsWith(CHECKBOX_CHECKED) -> {
                // Ensure existing content ends with single newline
                if (editable.isNotEmpty()) {
                    when (editable.last()) {
                        '\n' -> editable.append(lineText)
                        else -> editable.append("\n$lineText")
                    }
                } else {
                    editable.append(lineText)
                }
            }
            lineText.startsWith(CHECKBOX_UNCHECKED) -> {
                val titleEnd = editable.indexOf('\n').let { if (it >= 0) it else -1 } // Use -1 if no newline
                val insertPosition: Int

                val lineToAdd = if (lineText.endsWith("\n")) lineText else "$lineText\n"

                if (titleEnd >= 0) {
                    // There is a title line
                    insertPosition = titleEnd + 1
                    // Ensure the line we are inserting after the title ends with a newline
                    // and the line we are inserting starts on a new line.
                    // However, the lineToAdd already ensures it ends with a newline.
                    // We just need to make sure we don't add two newlines if the title line already had one.
                    if (editable.isNotEmpty() && editable.getOrNull(titleEnd) == '\n') {
                        // We will insert after this newline. lineToAdd also starts effectively "fresh"
                        editable.insert(insertPosition, lineToAdd)
                    } else {
                        // Title didn't end with a newline (e.g. it's the only text)
                        // or editable was empty before this.
                        // Or titleEnd was -1, meaning no newline found.
                        editable.insert(insertPosition, "\n$lineToAdd")
                    }

                } else {
                    // No title line, or text is empty. Insert at the beginning.
                    insertPosition = 0
                    // Prepend to existing content (if any), ensuring it starts on a new line relative to old content.
                    val existingContent = editable.toString()
                    editable.clear() // Clear and reconstruct
                    editable.append(lineToAdd)
                    if (existingContent.isNotEmpty()) {
                        if (lineToAdd.endsWith("\n") && existingContent.startsWith("\n")) {
                            editable.append(existingContent.substring(1)) // Avoid double newline
                        } else if (!lineToAdd.endsWith("\n") && !existingContent.startsWith("\n")) {
                            editable.append("\n").append(existingContent)
                        }
                        else {
                            editable.append(existingContent)
                        }
                    }
                }
            }
        }

        post {
            ignoreTextChange = true
            applyStrikethroughToCheckedItems(editable)
            formatTitle(editable)
            ignoreTextChange = false
        }
    }

    fun getTitleAndContent(): Pair<String, String> {
        val text = text?.toString() ?: return Pair("", "")
        val firstLineEnd = text.indexOf('\n')

        return if (firstLineEnd > 0) {
            Pair(
                text.substring(0, firstLineEnd).trim(),
                text.substring(firstLineEnd + 1).trim()
            )
        } else {
            Pair(text.trim(), "")
        }
    }

    fun setTitleAndContent(title: String, content: String) {
        val textToSet = if (content.isNotEmpty()) {
            "$title\n$content"
        } else {
            title
        }

        setText(textToSet)

        // Make sure the title is bold
        ignoreTextChange = true
        val editable = text
        if (editable != null) {
            formatTitle(editable)
            applyStrikethroughToCheckedItems(editable)
        }
        ignoreTextChange = false
    }
}
