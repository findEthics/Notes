@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.notes

import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.RoundedCorner
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.example.notes.ui.*
import kotlinx.coroutines.launch

internal class NoteScreen(
    private val activity: MainActivity,
    savedInstanceState: Bundle?,
    initialId: Long,
    private val finish: () -> Unit,
    private val createDestination: (CreationShortcut) -> Unit,
    initialText: String? = null,
    append: Boolean = false
) : ContextWrapper(activity), EditorSession {
    companion object { const val EXTRA_NOTE_ID = "extra_note_id" }
    private var noteId = -1L
    private val draft = TextFieldState()
    private var ready by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var settingsOpen by mutableStateOf(false)
    private var skipInitialResumeRefresh = true
    private var inputFocused = false
    private var lastSavedText: String? = null
    private val recovery by lazy { DraftRecovery(this, "note") }
    private var recoveryToken: String? = null
    private var untouchedAppend: String? = null
    private var pendingText: String? = savedInstanceState?.getString("pending_capture_text")
        ?: if (savedInstanceState == null) initialText else if (savedInstanceState.getBoolean("append_pending")) "" else null
    private var pendingAppend = savedInstanceState?.getBoolean("pending_capture_append") == true ||
        savedInstanceState?.getBoolean("append_pending") == true || append

    init {
        noteId = savedInstanceState?.getLong(EXTRA_NOTE_ID) ?: initialId
        require(noteId > 0)
        loadNote(applyCapture = false)
        // Only failed writes need a recovery draft. Successful recreation reads the committed DB body.
        savedInstanceState?.getString("note_recovery")?.let { token ->
            runCatching { recovery.claim(token) }.onSuccess { (next, text) ->
                recoveryToken = next
                draft.edit { replace(0, length, text) }; ready = true; error = getString(R.string.write_failed)
            }.onFailure {
                // Keep the original durable file; do not overwrite the committed row with a missing draft.
                ready = false; error = getString(R.string.load_failed)
            }
        }
        applyPendingCapture()
        val start = (savedInstanceState?.getInt("selection_start") ?: if (initialText != null) draft.text.length else 0).coerceIn(0, draft.text.length)
        val end = (savedInstanceState?.getInt("selection_end") ?: start).coerceIn(0, draft.text.length)
        draft.edit { selection = TextRange(start, end) }
        draft.undoState.clearHistory()
    }

    @Composable
    override fun Content(settings: FontSettings) {
            DisposableEffect(Unit) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        if (skipInitialResumeRefresh) skipInitialResumeRefresh = false else refreshIfClean()
                    }
                }
                activity.lifecycle.addObserver(observer)
                onDispose { activity.lifecycle.removeObserver(observer) }
            }
            val input = remember { FocusRequester() }
            val controls = remember { FocusRequester() }
            val inputMode = LocalInputModeManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            val focus = LocalFocusManager.current
            val view = LocalView.current
            val density = LocalDensity.current
            val imeVisible = WindowInsets.ime.getBottom(density) > 0
            val cornerRadius = with(density) {
                maxOf(
                    view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0,
                    view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0
                ).toDp()
            }
            // Physical corners still need clearance when system bars and the software keyboard are hidden.
            val actionBottomPadding = if (imeVisible) 8.dp else maxOf(16.dp, cornerRadius)
            val windowFocused = LocalWindowInfo.current.isWindowFocused
            Scaffold(modifier = Modifier.onPreviewKeyEvent { handleShortcut(it.nativeKeyEvent) },
                    contentWindowInsets = WindowInsets.safeDrawing.union(WindowInsets.ime),
                    ) { padding ->
                         BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("note-content")) {
                         val compactActions = maxHeight < 220.dp
                        Column(Modifier.fillMaxSize()) {
                            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("note-viewport")) {
                                error?.let { ErrorNotice(it) { if (ready) saveNote() else loadNote() } }
                                if (ready) NoteInput(draft, Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                    .focusRequester(input).focusRing().onFocusChanged { inputFocused = it.isFocused }
                                    .onPreviewKeyEvent {
                                        val event = it.nativeKeyEvent
                                        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_TAB &&
                                            !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed) {
                                            inputMode.requestInputMode(InputMode.Keyboard)
                                            focus.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next); true
                                        } else false
                                    })
                            }
                                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("note-actions")
                                    .padding(start = 20.dp, end = 20.dp, bottom = actionBottomPadding),
                                     verticalAlignment = Alignment.Bottom,
                                     horizontalArrangement = Arrangement.SpaceBetween) {
                                         FloatingActionButton(onClick = { if (ready) { draft.editNote { insertNoteCheckbox(it) }; input.requestFocus(); keyboard?.show() } },
                                             modifier = Modifier.focusRing().testTag("insert-checkbox"),
                                             containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                             contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                                             Icon(painterResource(R.drawable.ic_check_box), contentDescription = stringResource(R.string.insert_checkbox))
                                          }
                                          // Only float Share when the full stack would crowd the IME viewport.
                                          Column(Modifier.height(if (compactActions) 56.dp else 124.dp)
                                              .wrapContentHeight(Alignment.Bottom, unbounded = true),
                                              horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                              FloatingActionButton(onClick = ::shareNote,
                                                  modifier = Modifier.focusRing().testTag("share-note"),
                                                  containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                  contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                                                  Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.share_note))
                                              }
                                              FloatingActionButton(onClick = { if (ready && saveNote()) finish() },
                                                  modifier = Modifier.focusRequester(controls).focusRing().testTag("done"),
                                                  containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                  contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                                                  Icon(Icons.Default.Check, contentDescription = stringResource(R.string.done))
                                              }
                                          }
                                  }
                         }
                        }
                }
                // One request per destination/input lifetime, after the window can accept input.
                var initialFocusRequested by remember { mutableStateOf(false) }
                LaunchedEffect(ready, windowFocused) {
                    if (ready && windowFocused && !initialFocusRequested) {
                        withFrameNanos { }
                        input.requestFocus()
                        initialFocusRequested = true
                        view.post { if (inputFocused && !activity.isFinishing) keyboard?.show() }
                    }
                }
                if (settingsOpen) FontSettingsDialog(settings) { settingsOpen = false }
    }

    private fun shareNote() {
        if (!ready) return
        if (draft.text.toString().isBlank()) return
        if (!saveNote()) return
        val (title, _) = splitNote(draft.text.toString())
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            if (title.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, draft.text.toString())
        }
        runCatching { startActivity(Intent.createChooser(sendIntent, null)) }
    }

    private fun applyPendingCapture() {
        if (!ready) return
        val capture = pendingText ?: return
        val text = if (pendingAppend) draft.text.toString().let { it + if (it.endsWith('\n')) "" else "\n" } + capture else capture
        draft.edit { replace(0, length, text); selection = TextRange(length) }
        if (pendingAppend && capture.isEmpty()) untouchedAppend = text
        pendingText = null
    }
    private fun loadNote(applyCapture: Boolean = true) {
        runCatching { DatabaseHelper(this).use { it.getNoteById(noteId) } }
            .onSuccess {
                // A notification for our own save must not normalize the live draft or clear its undo history.
                if (!ready || splitNote(draft.text.toString()) != (it?.title.orEmpty() to it?.content.orEmpty())) {
                    draft.edit { replace(0, length, it?.let { note -> restoredNoteText(note.title, note.content) } ?: "") }
                    draft.undoState.clearHistory()
                }
                lastSavedText = draft.text.toString()
                ready = true; error = null
                if (applyCapture) applyPendingCapture()
            }
            .onFailure { Log.e("NoteScreen", "Load failed", it); error = getString(R.string.load_failed) }
    }
    /** Synchronous local commit before navigation and before capturing the replacement ID. */
    private fun saveNote(): Boolean {
        if (!ready) return false
        if (draft.text.toString() == untouchedAppend) return true
        val (title, body) = splitNote(draft.text.toString())
        return runCatching {
            DatabaseHelper(this).use { helper ->
                // A clean editor must not overwrite a headless append; deleted rows still get replacement IDs.
                if (recoveryToken == null && draft.text.isNotBlank() && draft.text.toString() == lastSavedText &&
                    helper.getNoteById(noteId) != null) return@use
                if (title.isEmpty() && body.isEmpty()) helper.deleteNotes(listOf(noteId))
                else if (helper.updateNote(noteId, title, body) == 0) noteId = helper.insertNote(title, body)
            }
        }.fold(onSuccess = { lastSavedText = draft.text.toString(); untouchedAppend = null; recovery.remove(recoveryToken); recoveryToken = null; error = null; true }, onFailure = {
            Log.e("NoteScreen", "Save failed; draft retained", it)
            error = getString(R.string.write_failed); false
        })
    }
    internal fun refreshIfClean() {
        if (ready && recoveryToken == null && pendingText == null && draft.text.toString() == lastSavedText) {
            val selection = draft.selection
            loadNote()
            draft.edit { this.selection = TextRange(selection.start.coerceAtMost(length), selection.end.coerceAtMost(length)) }
        }
    }
    private fun create(action: CreationShortcut) {
        if (!saveNote()) return
        createDestination(action)
    }
    override fun handleShortcut(event: KeyEvent): Boolean {
        if (ready && !settingsOpen) when (event.editorAction()) {
            EditorAction.Close -> { if (saveNote()) finish(); return true }
            EditorAction.Save -> { saveNote(); return true }
            EditorAction.ToggleCheck -> if (inputFocused) { draft.editNote { insertNoteCheckbox(it, toggle = true) }; return true }
            EditorAction.NewLineItem -> if (inputFocused) { draft.editNote { noteChecklistEnter(it) ?: notePlainNewline(it) }; return true }
            else -> Unit
        }
        if (ready && !settingsOpen && inputFocused && event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER) && event.hasModifiers(KeyEvent.META_SHIFT_ON)) {
            draft.editNote(::notePlainNewline); return true
        }
        if (ready && !settingsOpen && inputFocused && event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_DEL && event.hasNoModifiers()) {
            noteChecklistBackspace(draft.editorText())?.let { next -> draft.edit { applyEditorText(next) }; return true }
        }
        val action = event.creationShortcut(inputFocused)
        if (action != null && ready && !settingsOpen) { create(action); return true }
        return false
    }
    override fun showError(message: String) { error = message }
    override fun saveForLeave(): Boolean = if (!ready) pendingText == null else saveNote()
    override fun captureState(): Bundle = Bundle().also { outState ->
        val saved = saveNote()
        outState.putLong(EXTRA_NOTE_ID, noteId)
        pendingText?.let {
            outState.putString("pending_capture_text", it)
            outState.putBoolean("pending_capture_append", pendingAppend)
        }
        val appendPending = draft.text.toString() == untouchedAppend
        outState.putBoolean("append_pending", appendPending)
        val selection = if (saved && !appendPending) normalizedNoteSelection(draft.editorText()) else draft.selection
        outState.putInt("selection_start", selection.start)
        outState.putInt("selection_end", selection.end)
        if (!saved && ready) {
            recoveryToken = recovery.write(recoveryToken, draft.text.toString())
            outState.putString("note_recovery", recoveryToken)
        }
    }
}

@Composable
private fun NoteInput(value: TextFieldState, modifier: Modifier) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val caret = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value.selection, layout, focused) {
        if (focused) layout?.takeIf { it.layoutInput.text.text == value.text.toString() }
            ?.let { caret.bringIntoView(it.getCursorRect(value.selection.end)) }
    }
    val config = LocalViewConfiguration.current
    val label = stringResource(R.string.note_text)
    val titleStyle = MaterialTheme.typography.titleLarge.toSpanStyle()
    val output = remember(titleStyle) { noteOutputTransformation(titleStyle) }
    BasicTextField(state = value, inputTransformation = NoteInputTransformation,
        textStyle = notesEditorTextStyle().copy(color = MaterialTheme.colorScheme.onSurface),
        decorator = { field ->
            Box {
                if (value.text.isEmpty()) Text(stringResource(R.string.note_placeholder),
                    style = notesEditorTextStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                field()
            }
        },
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        outputTransformation = output,
        onTextLayout = { layout = it() }, modifier = modifier.bringIntoViewRequester(caret).onFocusChanged { focused = it.isFocused }
            .testTag("note-text").semantics { contentDescription = label }
            .pointerInput(config) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val tap = NoteCheckboxTap(down.position.x, down.position.y, down.uptimeMillis,
                        config.touchSlop, config.longPressTimeoutMillis)
                    if (down.isConsumed) tap.cancel()
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (event.changes.size != 1 || change == null) tap.cancel()
                        if (change != null) {
                            tap.move(change.position.x, change.position.y)
                            if (change.isConsumed) tap.cancel()
                            if (change.changedToUpIgnoreConsumed() && tap.finish(change.position.x, change.position.y, change.uptimeMillis)) {
                                val textLayout = layout
                                if (textLayout != null && change.position.y >= 0 && change.position.y < textLayout.size.height) {
                                    val offset = textLayout.getOffsetForPosition(change.position)
                                    val line = textLayout.getLineForOffset(offset)
                                    val start = textLayout.getLineStart(line)
                                    val end = textLayout.getLineEnd(line)
                                    val text = value.text.toString()
                                    val lineText = text.substring(start.coerceAtMost(text.length), end.coerceAtMost(text.length))
                                    if (lineText.hasNoteCheckbox(0) && lineText.trim().length > 1) {
                                        value.edit { replace(start, start + 1, if (lineText[0] == '☐') "☑" else "☐") }
                                        change.consume()
                                    }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            })
}
