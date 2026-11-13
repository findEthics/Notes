package com.example.notes

import android.view.KeyEvent
import androidx.compose.ui.text.TextRange
import org.junit.Assert.*
import org.junit.Test

class CaptureAndInlineShortcutsTest {
    @Test fun captureAuthorizationIsPackageAndSingleCertificateBound() {
        val own = "a".repeat(64)
        val debug = "f1849f419e633e9f078b53a0095d6e963683da46fe439b25e705e9b267665ce2"
        val release = "7c928de3824f4c6d91056d5e5615c5cb5414fece82643c0930c0ca600f583a0b"
        assertTrue(isAuthorizedCaller("com.example.notes", own, own))
        for (cert in listOf(own, debug, release)) {
            assertTrue(isAuthorizedCaller("com.galaxyrio.gracelauncher", cert, own))
            assertFalse(isAuthorizedCaller("untrusted", cert, own))
        }
        for (cert in listOf(debug, release, "b".repeat(64), "", "a".repeat(63), own.uppercase()))
            assertFalse(isAuthorizedCaller("com.example.notes", cert, own))
        for (cert in listOf("b".repeat(64), "", debug.uppercase(), "$debug "))
            assertFalse(isAuthorizedCaller("com.galaxyrio.gracelauncher", cert, own))
        assertFalse(isAuthorizedCaller("com.galaxyrio.gracelauncher.fake", own, own))
    }

    @Test fun sectionSwitchAcceptsOnlyExactAltOrShiftSpaceOutsideInput() {
        fun action(alt: Boolean = false, shift: Boolean = false, ctrl: Boolean = false,
            focused: Boolean = false, repeat: Int = 0, down: Boolean = true) =
            listAction(KeyEvent.KEYCODE_SPACE, ctrl, alt, shift, false, down, repeat, focused)
        assertEquals(ListAction.SwitchSection, action(alt = true))
        assertEquals(ListAction.SwitchSection, action(shift = true))
        assertNull(action(alt = true, shift = true))
        assertNull(action(alt = true, ctrl = true))
        assertNull(action(alt = true, focused = true))
        assertNull(action(shift = true, focused = true))
        assertNull(action(alt = true, repeat = 1))
        assertNull(action(alt = true, down = false))
        assertNull(action())
    }

    @Test fun appendedBlankLineSurvivesEditingAndSelectionRestoration() {
        for (text in listOf("Shopping\n\nMilk", "Shopping\nEggs\n\nMilk", "Shopping\n\n☐ Milk", "Shopping\n☑ Eggs\n\n☐ Milk")) {
            val (title, body) = splitNote(text)
            assertEquals(text, restoredNoteText(title, body))
            for (offset in text.indices)
                assertEquals(TextRange(offset), normalizedNoteSelection(EditorText(text, TextRange(offset))))
            assertEquals(TextRange(text.length), normalizedNoteSelection(EditorText(text, TextRange(text.length))))
        }
        assertEquals("Shopping" to "\nMilk", splitNote("Shopping\n\nMilk"))
        assertEquals("Shopping" to "\nMilk", splitNote("Shopping\n \t\n  Milk  \n"))
        assertEquals("Shopping" to "", splitNote("Shopping\n\n   "))
        assertEquals("" to "body\nsecond", splitNote("\n body\nsecond \n"))
    }

    @Test fun capturedNoteTitleAndChecklistBodyRoundtripWithoutDuplicatingTitle() {
        for (body in listOf("", "☐ buy milk and eggs", "☑ bought milk")) {
            val text = restoredNoteText("Groceries", body)
            assertEquals(if (body.isEmpty()) "Groceries" else "Groceries\n$body", text)
            assertEquals("Groceries" to body, splitNote(text))
        }
    }
}
