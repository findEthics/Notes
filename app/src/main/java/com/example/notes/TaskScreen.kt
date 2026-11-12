@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.notes

import android.content.ContextWrapper
import android.os.Bundle
import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.notes.ui.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.Executors

internal class TaskScreen(
    private val activity: MainActivity,
    savedInstanceState: Bundle?,
    initialId: Long?,
    private val finish: () -> Unit,
    private val createDestination: (CreationShortcut) -> Unit,
    initialText: String? = null
) : ContextWrapper(activity), EditorSession {
    companion object { const val EXTRA_TASK_ID = "extra_task_id" }
    private var draft by mutableStateOf(TaskDraft())
    private val textState = TextFieldState()
    private var submitRequests by mutableIntStateOf(0)
    private var ready by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var settingsOpen by mutableStateOf(false)
    private var lastSaved: TaskDraft? = null
    private var deleted = false
    private val focusedFields = mutableSetOf<Long>()
    private val executor = Executors.newSingleThreadExecutor()
    private var loadGeneration = 0
    private val recovery by lazy { DraftRecovery(this, "task") }
    private var recoveryToken: String? = null
    private var restoredTaskId: Long? = null

    init {
        restoredTaskId = (if (savedInstanceState?.containsKey(EXTRA_TASK_ID) == true)
            savedInstanceState.getLong(EXTRA_TASK_ID) else initialId ?: -1).takeIf { it > 0 }
        val restored = savedInstanceState?.getString("task_recovery")
        if (restored != null) {
            runCatching { recovery.claim(restored) }.onSuccess { (next, text) ->
                recoveryToken = next; draft = decodeDraft(text); textState.edit { replace(0, length, draft.text) }; ready = true
            }.onFailure { error = getString(R.string.load_failed) }
        } else if (restoredTaskId == null) ready = true
        else load()
        if (savedInstanceState == null && initialId == null && initialText != null) {
            draft = draft.copy(text = initialText)
            textState.edit { replace(0, length, initialText); selection = androidx.compose.ui.text.TextRange(length) }
        }
    }

    @Composable
    override fun Content(settings: FontSettings) {
            val controlsRequester = remember { FocusRequester() }
            val inputMode = LocalInputModeManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            val scope = rememberCoroutineScope()
            val leaveInput = {
                inputMode.requestInputMode(InputMode.Keyboard)
                scope.launch {
                    // Keyboard mode must reach the buttons before they can accept focus.
                    withFrameNanos { }
                    controlsRequester.requestFocus()
                    keyboard?.hide()
                }
            }
                Scaffold(modifier = Modifier.onPreviewKeyEvent { handleShortcut(it.nativeKeyEvent) },
                    contentWindowInsets = WindowInsets.safeDrawing,
                    bottomBar = {
                        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                            .heightIn(min = 56.dp).testTag("task-actions").padding(horizontal = 16.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            EditorFontPicker { keyboard?.hide(); settingsOpen = true }
                            LeaveInputButton(ready) { leaveInput() }
                            RowActions("task-menu", stringResource(R.string.task_actions), stringResource(R.string.delete_task),
                                "delete-task", enabled = ready, onAction = ::deleteTask)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { if (save()) finish() }, enabled = ready,
                                modifier = Modifier.heightIn(min = 48.dp).focusRequester(controlsRequester).focusRing().testTag("done")) {
                                Text(stringResource(R.string.done), style = MaterialTheme.typography.labelLarge) }
                        }
                    }) { padding ->
                    LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("task-fields"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        error?.let { message -> item { ErrorNotice(message) { if (ready) save() else load() } } }
                        if (!ready) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        else {
                            item {
                                TaskCheckRow(Modifier.fillMaxWidth(), checkbox = { alignment ->
                                    Checkbox(draft.completed, { draft = draft.copy(completed = it) },
                                        modifier = alignment.semantics { contentDescription = getString(R.string.complete_task) }
                                            .testTag("parent-completed"))
                                }) { alignment ->
                                    EditorField(0,
                                        stringResource(R.string.task_text), true,
                                        alignment.testTag("task-text"), draft.completed)
                                }
                            }
                        }
                    }
                }
                if (settingsOpen) FontSettingsDialog(settings) { settingsOpen = false }
    }

    @Composable
    private fun EditorField(key: Long, label: String,
        initiallyFocus: Boolean, modifier: Modifier, completed: Boolean) {
        val requester = remember { FocusRequester() }
        val focus = LocalFocusManager.current
        val inputMode = LocalInputModeManager.current
        DisposableEffect(key) { onDispose { focusedFields.remove(key) } }
        LaunchedEffect(submitRequests) {
            if (submitRequests > 0) { submitRequests = 0; submitTask() }
        }
        TaskEditorInput(textState, label, completed,
            inputTransformation = InputTransformation {
                if (isCaretNewline()) { revertAllChanges(); submitRequests++ }
            },
            modifier = modifier.focusRequester(requester)
                .onFocusChanged { if (it.isFocused) focusedFields.add(key) else focusedFields.remove(key) }
                .onPreviewKeyEvent {
                    val event = it.nativeKeyEvent
                    if (event.editorAction() == EditorAction.NewLineItem) {
                        submitTask()
                        true
                    } else if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                        event.hasModifiers(KeyEvent.META_SHIFT_ON)) {
                        textState.editNote(::notePlainNewline); true
                    } else if (event.keyCode in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER) && event.repeatCount > 0 && !event.isShiftPressed) {
                        true
                    } else if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_TAB &&
                         !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed) {
                        inputMode.requestInputMode(InputMode.Keyboard)
                        focus.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                        true
                    } else false
                })
        LaunchedEffect(Unit) { if (initiallyFocus) requester.requestFocus() }
    }

    private fun submitTask() {
        val blank = textState.text.isBlank()
        if (save()) {
            if (blank) finish() else {
                draft = TaskDraft(); lastSaved = null; restoredTaskId = null
                textState.edit { replace(0, length, "") }
                textState.undoState.clearHistory()
            }
        }
    }

    private fun load() {
        val token = ++loadGeneration
        executor.execute {
            val result = runCatching {
                DatabaseHelper(applicationContext).use { helper ->
                    val id = restoredTaskId ?: -1
                    val task = checkNotNull(helper.getTaskById(id))
                    TaskDraft(id, task.text, task.completed)
                }
            }
            activity.runOnUiThread {
                if (!activity.isDestroyed && !activity.isFinishing && token == loadGeneration) {
                    result.onSuccess { draft = it; lastSaved = it; textState.edit { replace(0, length, it.text) }; textState.undoState.clearHistory(); ready = true; error = null }
                        .onFailure { error = getString(R.string.task_missing) }
                }
            }
        }
    }
    /** Synchronous narrow transaction at lifecycle boundary: completion precedes navigation/pause. */
    private fun save(): Boolean {
        if (!ready || deleted) return ready
        draft = draft.copy(text = textState.text.toString())
        if (draft == lastSaved) return true
        return runCatching {
            DatabaseHelper(this).use { helper ->
                if (draft.text.isBlank()) {
                    draft.id?.let { helper.deleteTask(it) }
                    restoredTaskId = null
                    draft.copy(id = null)
                } else helper.saveTaskDraft(draft)
            }
        }
            .fold(onSuccess = { draft = it; lastSaved = it; recovery.remove(recoveryToken); recoveryToken = null; error = null; true },
                onFailure = { error = getString(R.string.write_failed); false })
    }
    private fun deleteTask() {
        val id = draft.id
        val result = runCatching { if (id != null) DatabaseHelper(this).use { check(it.deleteTask(id) == 1) } }
        result.onSuccess { deleted = true; recovery.remove(recoveryToken); recoveryToken = null; finish() }.onFailure { error = getString(R.string.write_failed) }
    }
    override fun showError(message: String) { error = message }
    override fun saveForLeave(): Boolean = !ready || save()
    override fun dispose() {
        loadGeneration++; executor.shutdown()
    }
    override fun captureState(): Bundle = Bundle().also { outState ->
        save()
        if (ready && !deleted) {
            // An explicit blank identity must override the original launch ID after process death.
            outState.putLong(EXTRA_TASK_ID, draft.id ?: -1)
            if ((draft != lastSaved || draft.id == null) && draft.text.isNotEmpty()) {
                recoveryToken = recovery.write(recoveryToken, encodeDraft(draft))
                outState.putString("task_recovery", recoveryToken)
            }
        }
    }

    override fun handleShortcut(event: KeyEvent): Boolean {
        if (!settingsOpen && ready) when (event.editorAction()) {
            EditorAction.Close -> { if (save()) finish(); return true }
            EditorAction.Save -> { save(); return true }
            else -> Unit
        }
        val shortcut = event.creationShortcut(focusedFields.isNotEmpty())
        if (shortcut != null && !settingsOpen && ready) {
            if (save()) {
                createDestination(shortcut)
            }
            return true
        }
        return false
    }

    private fun encodeDraft(value: TaskDraft): String = JSONObject().apply {
        put("id", value.id ?: -1); put("text", value.text); put("completed", value.completed)
    }.toString()
    private fun decodeDraft(value: String): TaskDraft = JSONObject(value).let { json ->
        TaskDraft(json.getLong("id").takeIf { it > 0 }, json.getString("text"), json.getBoolean("completed"))
    }
}
