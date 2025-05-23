package com.example.notes

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
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

    init {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (ignoreTextChange) return
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val offset = getOffsetForPosition(event.x, event.y)
            val line = layout.getLineForOffset(offset)
            val lineStart = layout.getLineStart(line)
            val lineEnd = layout.getLineEnd(line)
            val text = text?.subSequence(lineStart, lineEnd).toString()
            if (text.startsWith(CHECKBOX_UNCHECKED) || text.startsWith(CHECKBOX_CHECKED)) {
                toggleCheckbox(lineStart, lineEnd)
//                handleTaskMovement(lineStart, lineEnd)
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

    fun appendNewCheckbox() {
        val editable = text ?: return // Get the editable text, or return if null

        ignoreTextChange = true // Prevent afterTextChanged from firing for this direct manipulation

        // Ensure there's a newline before adding the new checkbox if text is not empty
        // and doesn't already end with a newline.
        if (editable.isNotEmpty() && editable.last() != '\n') {
            editable.append("\n")
        }
        editable.append("$CHECKBOX_UNCHECKED ") // Append checkbox and a space

        // Manually trigger formatting updates
        formatTitle(editable)
        applyStrikethroughToCheckedItems(editable)
        ignoreTextChange = false

        // Optionally, move cursor to the end or after the new checkbox
        setSelection(editable.length)
    }
}
