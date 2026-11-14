package com.example.notes

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.notes.ui.notesTypography
import org.junit.Assert.*
import org.junit.Test

class NoteEditorTest {
    @Test fun nextLineCheckboxPreservesCurrentAndFollowingLines() {
        for (caret in listOf(0, 2, 5)) {
            assertEquals(EditorText("Title\n☐ \nBody", TextRange(8)),
                insertNextLineCheckbox(EditorText("Title\nBody", TextRange(caret))))
        }
        assertEquals(EditorText("Title\n☐ ", TextRange(8)),
            insertNextLineCheckbox(EditorText("Title", TextRange(5))))
        assertEquals(EditorText("\n☐ ", TextRange(3)), insertNextLineCheckbox(EditorText()))
        assertEquals(EditorText("Title\n\n☐ ", TextRange(9)),
            insertNextLineCheckbox(EditorText("Title\n", TextRange(6))))
    }
    @Test fun plainTextRoundtripAndStyle() {
        assertEquals("" to "body\nsecond", splitNote("\n body\nsecond \n"))
        assertEquals("Title" to "body", splitNote(" Title \n body "))
        assertEquals("☐ \n☑ ", restoredNoteText("☐", "☑"))
        val title = notesTypography(FontChoice.LATO).titleLarge.toSpanStyle()
        val styled = styledNoteText(AnnotatedString("Title\n☑ task"), title)
        assertEquals("Title\n☑ task", styled.text)
        assertEquals(2, styled.spanStyles.size)
        assertEquals(FontWeight.Black, styled.spanStyles.first().item.fontWeight)
        assertEquals(28.sp, styled.spanStyles.first().item.fontSize)
        assertEquals(title, styled.spanStyles.first().item)
        assertEquals(0, styled.spanStyles.first().start)
        assertEquals(5, styled.spanStyles.first().end)
        assertTrue(styledNoteText(AnnotatedString("\ntext"), title).spanStyles.isEmpty())
    }
    @Test fun normalizedSelectionMapsBothEndpointsAndCheckboxSpaces() {
        val raw = EditorText("  Title  \n  Body  ", TextRange(12, 3))
        assertEquals(TextRange(6, 1), normalizedNoteSelection(raw))
        assertEquals(TextRange(6), normalizedNoteSelection(raw.copy(selection = TextRange(12))))
        assertEquals(TextRange(0, 10), normalizedNoteSelection(raw.copy(selection = TextRange(0, raw.text.length))))
        assertEquals("  Title  \n  Body  ", raw.text)
        assertEquals(TextRange(2, 5), normalizedNoteSelection(EditorText(" ☐ \n ☑ ", TextRange(2, 6))))
        assertEquals(TextRange(0), normalizedNoteSelection(EditorText(" \n ", TextRange(2))))
        assertEquals(TextRange(3), normalizedNoteSelection(EditorText(" abc ", TextRange(5))))
    }
    @Test fun gestureExclusionsStaySticky() {
        fun tap() = NoteCheckboxTap(0f, 0f, 10L, 8f, 500L)
        assertTrue(tap().finish(0f, 0f, 20))
        assertFalse(tap().apply { move(9f, 0f); move(0f, 0f) }.finish(0f, 0f, 20))
        assertFalse(tap().finish(0f, 0f, 510))
        assertFalse(tap().finish(0f, 0f, 9))
        assertFalse(tap().apply { move(0f, 0f, false) }.finish(0f, 0f, 20))
        assertFalse(tap().apply { cancel() }.finish(0f, 0f, 20))
    }
    @Test fun checklistEnterContinuesAtEnd() {
        assertEquals(EditorText("Title\n☐ milk\n☐ ", TextRange(15)),
            noteChecklistEnter(EditorText("Title\n☐ milk", TextRange(12))))
    }
    @Test fun checklistEnterSplitsInMiddleAndPreservesFollowingLines() {
        assertEquals(EditorText("Title\n☐ mi\n☐ lk\nnext", TextRange(13)),
            noteChecklistEnter(EditorText("Title\n☐ milk\nnext", TextRange(10))))
    }
    @Test fun checkedContinuationIsUnchecked() {
        assertEquals(EditorText("☑ done\n☐ ", TextRange(9)),
            noteChecklistEnter(EditorText("☑ done", TextRange(6))))
    }
    @Test fun markerOnlyEnterEndsListWithoutNewline() {
        for (marker in listOf("☐ ", "☑ ", "☐   \t")) {
            assertEquals(EditorText("Title\n\nnext", TextRange(6)),
                noteChecklistEnter(EditorText("Title\n${marker}\nnext", TextRange(8))))
        }
    }
    @Test fun backspaceRemovesOnlyEmptyMarker() {
        for (marker in listOf("☐ ", "☑ ")) {
            assertEquals(EditorText("Title\n \t\nnext", TextRange(6)),
                noteChecklistBackspace(EditorText("Title\n${marker} \t\nnext", TextRange(8))))
        }
        assertNull(noteChecklistBackspace(EditorText("☐ task", TextRange(2))))
        assertNull(noteChecklistBackspace(EditorText("☐ ", TextRange(1))))
    }
    @Test fun shiftEnterAndNonCheckboxLinesUseNativeNewline() {
        assertEquals(EditorText("☐ task\n", TextRange(7)), notePlainNewline(EditorText("☐ task", TextRange(6))))
        assertEquals(EditorText("☐ \n", TextRange(3)), notePlainNewline(EditorText("☐ task", TextRange(6, 2))))
        assertNull(noteChecklistEnter(EditorText("☐ task", TextRange(6)), shift = true))
        assertNull(noteChecklistEnter(EditorText("normal", TextRange(6))))
        assertNull(noteChecklistEnter(EditorText("☐task", TextRange(5))))
        assertNull(noteChecklistEnter(EditorText("☐ task", TextRange(1))))
    }
    @Test fun selectionKeepsNativeEnterAndBackspace() {
        val value = EditorText("☐ ", TextRange(3, 1))
        assertNull(noteChecklistEnter(value))
        assertNull(noteChecklistBackspace(value))
    }
    @Test fun toggleInsertsOnCurrentLineAndPreservesCaret() {
        assertEquals(EditorText("Title\n☐ task", TextRange(10)),
            insertNoteCheckbox(EditorText("Title\ntask", TextRange(8)), toggle = true))
    }
    @Test fun toggleUncheckedToCheckedAndBackPreservesCaret() {
        val value = EditorText("Title\n☐ task", TextRange(10))
        val checked = insertNoteCheckbox(value, toggle = true)
        assertEquals(value.copy(text = "Title\n☑ task"), checked)
        assertEquals(value, insertNoteCheckbox(checked, toggle = true))
    }
    @Test fun toolbarIsIdempotentAndInsertsOnEmptyCurrentLine() {
        for (marker in listOf("☐ ", "☑ ")) {
            val value = EditorText("Title\n${marker}task", TextRange(10))
            assertEquals(value, insertNoteCheckbox(value))
        }
        assertEquals(EditorText("Title\n☐ \nnext", TextRange(8)),
            insertNoteCheckbox(EditorText("Title\n\nnext", TextRange(6))))
        assertEquals(EditorText("☐ ", TextRange(2)), insertNoteCheckbox(EditorText()))
    }
    @Test fun checkboxInsertionMapsReversedSelection() {
        val value = EditorText("Title\nbody", TextRange(9, 7))
        assertEquals(EditorText("Title\n☐ body", TextRange(11, 9)), insertNoteCheckbox(value))
        assertEquals(TextRange(10, 4), insertNoteCheckbox(value.copy(selection = TextRange(8, 2))).selection)
    }
}
