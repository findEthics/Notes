@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.notes

import android.content.ContextWrapper
import android.os.Bundle
import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.example.notes.ui.*
import org.json.JSONObject

/** Inline list editor; only failed drafts go into durable recovery, never Binder state. */
internal class TaskScreen(
    private val activity: MainActivity,
    savedInstanceState: Bundle?,
    initialId: Long?,
    private val finish: () -> Unit,
    private val onSaved: () -> Unit,
    initialText: String? = null
) : ContextWrapper(activity), EditorSession {
    companion object { const val EXTRA_TASK_ID = "extra_task_id" }
    private var draft by mutableStateOf(TaskDraft())
    private val textState = TextFieldState()
    private var ready by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var lastSaved: TaskDraft? = null
    private var inputFocused = false
    private var submitRequests by mutableIntStateOf(0)
    private val recovery by lazy { DraftRecovery(this, "task") }
    private var recoveryToken: String? = null
    val editingTaskId: Long get() = draft.id ?: -1L
    val textFocused: Boolean get() = inputFocused

    init {
        val id = (savedInstanceState?.getLong(EXTRA_TASK_ID) ?: initialId ?: -1L).takeIf { it > 0 }
        draft = TaskDraft(id = id)
        val restored = savedInstanceState?.getString("task_recovery")
        recoveryToken = restored
        runCatching {
            if (restored != null) {
                val (token, text) = recovery.claim(restored)
                recoveryToken = token
                val json = JSONObject(text)
                draft = TaskDraft(json.getLong("id").takeIf { it > 0 }, json.getString("text"), json.getBoolean("completed"))
            } else if (id != null) {
                val task = DatabaseHelper(this).use { checkNotNull(it.getTaskById(id)) }
                draft = TaskDraft(id, task.text, task.completed); lastSaved = draft
            } else if (savedInstanceState == null && initialText != null) draft = draft.copy(text = initialText)
            textState.edit { replace(0, length, draft.text); selection = androidx.compose.ui.text.TextRange(length) }
            ready = true
        }.onFailure { error = getString(R.string.load_failed) }
    }

    @Composable
    override fun Content(settings: FontSettings) {
        val requester = remember { FocusRequester() }
        val focus = LocalFocusManager.current
        val inputMode = LocalInputModeManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(ready, windowFocused) {
            if (ready && windowFocused) { withFrameNanos { }; requester.requestFocus() }
        }
        LaunchedEffect(submitRequests) { if (submitRequests > 0) { submitRequests = 0; submitTask() } }
        Column(Modifier.fillMaxWidth().testTag("task-fields")) {
            error?.let { message -> ErrorNotice(message) { save() } }
            if (!ready) LinearProgressIndicator(Modifier.fillMaxWidth())
            else TaskCheckRow(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 12.dp), checkbox = { alignment ->
                Checkbox(draft.completed, { toggleCompleted() }, modifier = alignment.testTag("parent-completed")
                    .semantics { contentDescription = getString(R.string.complete_task) })
            }) { alignment ->
                TaskEditorInput(textState, stringResource(R.string.task_text), draft.completed,
                    inputTransformation = InputTransformation {
                        if (isCaretNewline()) { revertAllChanges(); submitRequests++ }
                    }, modifier = alignment.testTag("task-text").focusRequester(requester)
                        .onFocusChanged { inputFocused = it.isFocused }
                        .onPreviewKeyEvent {
                            val event = it.nativeKeyEvent
                            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_TAB &&
                                !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed) {
                                inputMode.requestInputMode(InputMode.Keyboard)
                                focus.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                                keyboard?.hide(); true
                            } else false
                        })
                TextButton(onClick = { if (save()) finish() }, modifier = Modifier.heightIn(min = 48.dp)
                    .focusRing().testTag("done")) { Text(stringResource(R.string.done)) }
            }
        }
    }

    private fun submitTask() {
        val blank = textState.text.isBlank()
        if (save()) {
            if (blank) finish() else {
                draft = TaskDraft(); lastSaved = null
                textState.edit { replace(0, length, "") }; textState.undoState.clearHistory()
                onSaved()
            }
        }
    }

    private fun save(): Boolean {
        if (!ready) return false
        draft = draft.copy(text = textState.text.toString())
        if (draft == lastSaved) return true
        return runCatching {
            DatabaseHelper(this).use { helper ->
                if (draft.text.isBlank()) {
                    draft.id?.let { helper.deleteTask(it) }
                    draft.copy(id = null)
                } else helper.saveTaskDraft(draft)
            }
        }.fold(onSuccess = {
            draft = it; lastSaved = it; recovery.remove(recoveryToken); recoveryToken = null; error = null
            onSaved(); true
        }, onFailure = { error = getString(R.string.write_failed); false })
    }

    private fun toggleCompleted() {
        draft = draft.copy(completed = !draft.completed)
        activity.window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }
    override fun showError(message: String) { error = message }
    override fun saveForLeave(): Boolean = save()
    override fun dispose() { inputFocused = false }
    override fun captureState(): Bundle = Bundle().also { state ->
        save()
        state.putLong(EXTRA_TASK_ID, draft.id ?: -1L)
        if (ready && draft != lastSaved && draft.text.isNotEmpty()) {
            recoveryToken = recovery.write(recoveryToken, JSONObject().apply {
                put("id", draft.id ?: -1); put("text", draft.text); put("completed", draft.completed)
            }.toString())
            state.putString("task_recovery", recoveryToken)
        } else if (!ready) recoveryToken?.let { state.putString("task_recovery", it) }
    }
    override fun handleShortcut(event: KeyEvent): Boolean {
        if (!ready) return false
        when (event.editorAction()) {
            EditorAction.Close -> { if (save()) finish(); return true }
            EditorAction.Save -> {
                if (draft.id == null && textState.text.isNotBlank()) submitTask() else save()
                return true
            }
            EditorAction.ToggleCheck -> { toggleCompleted(); return true }
            EditorAction.NewLineItem -> if (inputFocused) { submitTask(); return true }
            EditorAction.NextLineCheckbox -> return true
            null -> Unit
        }
        if (inputFocused && event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            if (event.hasModifiers(KeyEvent.META_SHIFT_ON)) { textState.editNote(::notePlainNewline); return true }
            if (event.repeatCount > 0 && !event.isShiftPressed) return true
        }
        return false
    }
}
