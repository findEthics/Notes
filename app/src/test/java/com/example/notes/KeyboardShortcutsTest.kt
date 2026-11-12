package com.example.notes

import org.junit.Assert.*
import org.junit.Test

class KeyboardShortcutsTest {
    @Test fun listActionsAreExactFocusGatedAndNeverRepeat() {
        for (key in listOf(62, 67, 112, 32, 54, 111, 42)) for (mask in 0..15)
            for (down in listOf(false, true)) for (repeat in 0..1)
                for (focused in listOf(false, true)) for (extra in listOf(false, true)) {
                    val expected = if (!down || repeat != 0 || focused || extra) null else when {
                        key == 62 && (mask == 4 || mask == 2) -> ListAction.SwitchSection
                        (key == 67 || key == 112) && mask == 0 -> ListAction.Delete
                        key == 32 && mask == 1 -> ListAction.ToggleDone
                        key == 54 && mask == 1 -> ListAction.Undo
                        key == 111 && mask == 0 -> ListAction.CancelSelection
                        else -> null
                    }
                    assertEquals("key=$key mask=$mask down=$down repeat=$repeat focus=$focused extra=$extra", expected,
                        listAction(key, mask and 1 != 0, mask and 2 != 0, mask and 4 != 0,
                            mask and 8 != 0, down, repeat, focused, extra))
                }
    }
    @Test fun editorActionsRequireExactModifiersInitialDownAndNoRepeats() {
        for (key in listOf(111, 66, 160, 47, 32, 42)) for (mask in 0..15)
            for (down in listOf(false, true)) for (repeat in 0..1) {
                val ctrl = mask and 1 != 0; val alt = mask and 2 != 0
                val shift = mask and 4 != 0; val meta = mask and 8 != 0
                val expected = if (!down || repeat != 0) null else when {
                    key == 111 && mask == 0 -> EditorAction.Close
                    (key == 66 || key == 160) && mask == 2 -> EditorAction.Close
                    (key == 66 || key == 160) && mask == 0 -> EditorAction.NewLineItem
                    key == 47 && mask == 1 -> EditorAction.Save
                    key == 32 && mask == 1 -> EditorAction.ToggleCheck
                    else -> null
                }
                assertEquals("key=$key mask=$mask down=$down repeat=$repeat", expected,
                    editorAction(key, ctrl, alt, shift, meta, down, repeat))
            }
    }
    @Test fun onlyExactInitialAltOrShiftNAndTAreMapped() {
        for (letter in listOf('n', 't', 'x')) for (alt in listOf(false, true))
            for (shift in listOf(false, true)) for (initial in listOf(false, true)) {
                val expected = if (initial && alt != shift) when (letter) {
                    'n' -> CreationShortcut.NOTE; 't' -> CreationShortcut.TASK; else -> null
                } else null
                assertEquals(expected, creationShortcut(letter, initial, alt, shift))
            }
    }
    @Test fun anyTextFocusAndEveryExtraModifierDisableCreation() {
        for (letter in listOf('n', 't')) for (alt in listOf(false, true)) {
            assertNull(creationShortcut(letter, true, alt, !alt, textInputFocused = true))
            assertNull(creationShortcut(letter, true, alt, !alt, ctrl = true))
            assertNull(creationShortcut(letter, true, alt, !alt, meta = true))
            assertNull(creationShortcut(letter, true, alt, !alt, extra = true))
        }
    }
}
