package com.example.notes

import android.view.KeyEvent

enum class CreationShortcut { NOTE, TASK }

enum class EditorAction { Close, Save, ToggleCheck, InsertCheckbox, NextLineCheckbox, NewLineItem }

enum class ListAction { SwitchSection, Delete, ToggleDone, CancelSelection, Undo }

// Lock states are not held shortcut modifiers (unlike Shift, Sym or Function).
internal fun shortcutModifiers(metaState: Int): Int = metaState and
    (KeyEvent.META_CAPS_LOCK_ON or KeyEvent.META_NUM_LOCK_ON or KeyEvent.META_SCROLL_LOCK_ON).inv()

fun listAction(key: Int, ctrl: Boolean, alt: Boolean, shift: Boolean, meta: Boolean,
    isKeyDown: Boolean, repeatCount: Int, textInputFocused: Boolean = false,
    extra: Boolean = false): ListAction? {
    if (!isKeyDown || repeatCount != 0 || textInputFocused || extra || meta) return null
    return when {
        key == KeyEvent.KEYCODE_SPACE && (shift != alt) && !ctrl -> ListAction.SwitchSection
        alt -> null
        shift -> null
        key == KeyEvent.KEYCODE_D && ctrl -> ListAction.ToggleDone
        key == KeyEvent.KEYCODE_Z && ctrl -> ListAction.Undo
        ctrl -> null
        key == KeyEvent.KEYCODE_DEL || key == KeyEvent.KEYCODE_FORWARD_DEL -> ListAction.Delete
        key == KeyEvent.KEYCODE_ESCAPE -> ListAction.CancelSelection
        else -> null
    }
}

fun KeyEvent.listAction(textInputFocused: Boolean): ListAction? {
    val allowed = KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_SHIFT_MASK or KeyEvent.META_META_MASK
    return listAction(keyCode, isCtrlPressed, isAltPressed, isShiftPressed, isMetaPressed,
        action == KeyEvent.ACTION_DOWN, repeatCount, textInputFocused, shortcutModifiers(metaState) and allowed.inv() != 0)
}

/** Exact modifiers, initial key down only; independent of editor state. */
fun editorAction(
    key: Int, ctrl: Boolean, alt: Boolean, shift: Boolean, meta: Boolean,
    isKeyDown: Boolean, repeatCount: Int
): EditorAction? {
    if (!isKeyDown || repeatCount != 0 || meta || shift) return null
    return when {
        key == KeyEvent.KEYCODE_ESCAPE && !ctrl && !alt -> EditorAction.Close
        (key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER) && !ctrl -> if (alt) EditorAction.Close else EditorAction.NewLineItem
        key == KeyEvent.KEYCODE_S && ctrl && !alt -> EditorAction.Save
        key == KeyEvent.KEYCODE_D && ctrl && !alt -> EditorAction.ToggleCheck
        key == KeyEvent.KEYCODE_Q && ctrl && !alt -> EditorAction.InsertCheckbox
        (key == KeyEvent.KEYCODE_I || key == KeyEvent.KEYCODE_O) && ctrl && !alt -> EditorAction.NextLineCheckbox
        else -> null
    }
}

fun KeyEvent.editorAction(): EditorAction? {
    val allowed = KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_SHIFT_MASK or KeyEvent.META_META_MASK
    if (shortcutModifiers(metaState) and allowed.inv() != 0) return null
    return editorAction(keyCode, isCtrlPressed, isAltPressed, isShiftPressed, isMetaPressed,
        action == KeyEvent.ACTION_DOWN, repeatCount)
}

/** Pure mapper shared with the later note editor. Exactly one modifier, initial down only. */
fun creationShortcut(
    letter: Char, initialDown: Boolean, alt: Boolean, shift: Boolean,
    ctrl: Boolean = false, meta: Boolean = false, extra: Boolean = false,
    textInputFocused: Boolean = false
): CreationShortcut? {
    if (!initialDown || textInputFocused || ctrl || meta || extra || alt == shift) return null
    return when (letter) { 'n' -> CreationShortcut.NOTE; 't' -> CreationShortcut.TASK; else -> null }
}

fun KeyEvent.creationShortcut(textInputFocused: Boolean): CreationShortcut? {
    val allowed = KeyEvent.META_ALT_MASK or KeyEvent.META_SHIFT_MASK
    return creationShortcut(
        when (keyCode) { KeyEvent.KEYCODE_N -> 'n'; KeyEvent.KEYCODE_T -> 't'; else -> '?' },
        action == KeyEvent.ACTION_DOWN && repeatCount == 0, isAltPressed, isShiftPressed,
        isCtrlPressed, isMetaPressed, shortcutModifiers(metaState) and allowed.inv() != 0, textInputFocused
    )
}
