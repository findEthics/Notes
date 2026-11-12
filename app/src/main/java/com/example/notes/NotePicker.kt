package com.example.notes

import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.notes.ui.focusRing

@Composable
internal fun NotePreview(note: Note) {
    Text(note.title.ifEmpty { stringResource(R.string.untitled_note) },
        style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (note.content.isNotEmpty()) Text(note.content, style = MaterialTheme.typography.bodyMedium,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun NotePicker(notes: List<Note>, loading: Boolean, error: String?, retry: () -> Unit,
    open: (Long) -> Unit, close: () -> Unit, create: () -> Unit) {
    val scroll = rememberLazyListState()
    val rootFocus = remember { FocusRequester() }
    val requesters = remember { mutableMapOf<Long, FocusRequester>() }
    var focused by remember { mutableIntStateOf(0) }
    var focusRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(notes, loading, focusRevision) {
        rootFocus.requestFocus()
        if (!loading && notes.isNotEmpty()) {
            focused = focused.coerceIn(notes.indices)
            scroll.scrollToItem(focused)
            withFrameNanos { }
            requesters[notes[focused].id]?.requestFocus()
        }
    }
    Scaffold(Modifier.testTag("note-picker").onPreviewKeyEvent {
        val event = it.nativeKeyEvent
        val handled = event.hasNoModifiers() && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_ESCAPE)
        if (handled) {
            if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { focused = (focused + 1).coerceAtMost((notes.size - 1).coerceAtLeast(0)); focusRevision++ }
                KeyEvent.KEYCODE_DPAD_UP -> { focused = (focused - 1).coerceAtLeast(0); focusRevision++ }
                KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) notes.getOrNull(focused)?.let { open(it.id) }
                KeyEvent.KEYCODE_ESCAPE -> if (event.repeatCount == 0) close()
            }
            true
        } else if (event.creationShortcut(false) == CreationShortcut.NOTE) { create(); true } else false
    }.focusRequester(rootFocus).focusable(), contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { Text(stringResource(R.string.shortcut_append_long), Modifier.statusBarsPadding().padding(16.dp),
            style = MaterialTheme.typography.titleLarge) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            error?.let { com.example.notes.ui.ErrorNotice(it, retry) }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (notes.isEmpty()) Text("No notes yet · Alt+N to create", Modifier.padding(16.dp))
            LazyColumn(state = scroll, contentPadding = PaddingValues(horizontal = 16.dp)) {
                itemsIndexed(notes, key = { _, note -> note.id }) { index, note ->
                    val requester = remember(note.id) { FocusRequester() }
                    DisposableEffect(note.id) { requesters[note.id] = requester; onDispose { requesters.remove(note.id) } }
                    Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).focusRequester(requester)
                        .onFocusChanged { if (it.isFocused) focused = index }.focusRing().testTag("pick-note-${note.id}")
                        .clickable { open(note.id) }.padding(vertical = 12.dp)) { NotePreview(note) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}
