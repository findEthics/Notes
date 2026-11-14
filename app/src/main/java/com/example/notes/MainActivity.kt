package com.example.notes

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModelProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.example.notes.ui.*
import java.util.concurrent.Executors
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import androidx.compose.animation.*
import androidx.compose.animation.core.tween

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_SECTION = "section" }
    private var notes by mutableStateOf(emptyList<Note>())
    private var tasks by mutableStateOf(emptyList<TaskSummary>())
    private var loading by mutableStateOf(true)
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var section by mutableStateOf(Section.NOTES)
    private var selectionMode by mutableStateOf(false)
    private var selectedIds by mutableStateOf(setOf<Long>())
    private val executor = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var resumed by mutableStateOf(false)
    private var pendingCaptures by mutableStateOf<List<QuickCapture>>(emptyList())
    private var settingsOpen by mutableStateOf(false)
    private data class PendingDelete(val section: Section, val ids: List<Long>)
    private var pendingDelete by mutableStateOf<PendingDelete?>(null)
    private val snackbar = SnackbarHostState()
    private val noteScroll = LazyListState()
    private val taskScroll = LazyListState()
    private var focusedRow: String? = null
    private val shortcutKeyUps = mutableSetOf<Int>()
    private var restoreRow: String? = null
    private var focusRevision by mutableIntStateOf(0)
    private lateinit var creation: NoteCreation
    internal lateinit var navController: NavHostController
        private set
    internal var currentEditor: EditorSession? = null
        private set
    private var inlineTask by mutableStateOf<TaskScreen?>(null)
    private val dataChanged: () -> Unit = {
        if (!isDestroyed && !isFinishing) { load(); (currentEditor as? NoteScreen)?.refreshIfClean() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialCapture = QuickCapture.from(intent)
        // Restored navigation owns the destination; never replay the Activity's original launch intent.
        pendingCaptures = savedInstanceState?.getParcelableArrayList<Bundle>("captures")?.mapNotNull(QuickCapture::restore)
            ?: if (savedInstanceState == null) listOfNotNull(initialCapture) else emptyList()
        creation = ViewModelProvider(this)[NoteCreation::class.java]
        creation.result.observe(this) { result ->
            if (resumed && result != null) {
                creation.consume()?.onSuccess { openNote(it) }
                    ?.onFailure { error = getString(R.string.write_failed) }
            }
        }
        section = Section.entries.getOrElse(savedInstanceState?.getInt("section") ?: if (initialCapture != null) 0
            else runCatching { intent.getIntExtra(EXTRA_SECTION, 0) }.getOrDefault(0)) { Section.NOTES }
        selectionMode = savedInstanceState?.getBoolean("selection_mode") ?: false
        selectedIds = savedInstanceState?.getLongArray("selected_ids")?.toSet() ?: emptySet()
        savedInstanceState?.getBundle("inline_task")?.let { state ->
            inlineTask = TaskScreen(this, state, null, ::closeInlineTask, ::load)
            currentEditor = inlineTask
            section = Section.TASKS
        }
        NotesDataEvents.listeners.add(dataChanged)
        savedInstanceState?.getLongArray("pending_delete_ids")?.let { ids ->
            pendingDelete = PendingDelete(Section.entries[savedInstanceState.getInt("pending_delete_section")], ids.toList())
        }
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        setContent {
            val settings = rememberFontSettings()
            NotesTheme(fontChoice = settings.choice) {
                navController = rememberNavController()
                var backProgress by remember { mutableFloatStateOf(0f) }
                val currentEntry by navController.currentBackStackEntryAsState()
                LaunchedEffect(pendingCaptures, resumed, currentEntry) {
                    if (resumed && pendingCaptures.isNotEmpty()) {
                        withFrameNanos { }
                        if (currentEntry?.destination?.route == "note/{id}") {
                            // The previous capture must reach its editor before replacement saves it.
                            while (currentEditor == null) withFrameNanos { }
                            withFrameNanos { }
                        }
                        openCapture()
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    // Preview is read-only; no lifecycle/persistence work until the gesture commits.
                    if (backProgress > 0f) ListContent(settings, preview = true)
                    NavHost(navController, startDestination = "list",
                        modifier = Modifier.graphicsLayer {
                            translationX = size.width * backProgress * 0.25f
                            alpha = 1f - backProgress * 0.3f
                        },
                        enterTransition = { fadeIn(tween(240)) + slideInHorizontally(tween(240)) { it / 12 } },
                        exitTransition = { fadeOut(tween(240)) + slideOutHorizontally(tween(240)) { -it / 12 } },
                        popEnterTransition = { fadeIn(tween(240)) + slideInHorizontally(tween(240)) { -it / 12 } },
                        popExitTransition = { fadeOut(tween(240)) + slideOutHorizontally(tween(240)) { it / 12 } }) {
                        composable("list") { entry ->
                            DisposableEffect(entry) {
                                val observer = LifecycleEventObserver { _, event ->
                                    if (event == Lifecycle.Event.ON_RESUME) load()
                                }
                                entry.lifecycle.addObserver(observer)
                                onDispose { entry.lifecycle.removeObserver(observer) }
                            }
                            ListContent(settings)
                        }
                        composable("note/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                            val screen = remember(entry) { NoteScreen(this@MainActivity,
                                entry.savedStateHandle.get<Bundle>("editor"), entry.arguments!!.getLong("id"),
                                ::closeEditor, ::replaceEditor,
                                entry.savedStateHandle.remove<String>("capture_text"),
                                entry.savedStateHandle.remove<Boolean>("capture_append") == true) }
                            EditorContent(entry, screen, settings)
                        }
                        composable("note-picker") { entry ->
                            LaunchedEffect(entry) { load() }
                            NotePicker(notes, loading, error, ::load, { id ->
                                navController.navigate("note/$id") { popUpTo("list") }
                                navController.currentBackStackEntry?.savedStateHandle?.apply {
                                    set("capture_text", entry.savedStateHandle.get<String>("capture_text").orEmpty())
                                    set("capture_append", true)
                                }
                            }, ::closeEditor, { replaceEditor(CreationShortcut.NOTE) })
                        }
                    }
                }
                PredictiveBackHandler(currentEntry?.destination?.route != null && currentEntry?.destination?.route != "list") { events ->
                    try {
                        events.collect { backProgress = it.progress }
                        closeEditor()
                    } catch (_: CancellationException) {
                        // A canceled gesture must not save/delete even a blank note.
                    } finally { backProgress = 0f }
                }
            }
        }
    }

    @Composable
    private fun EditorContent(entry: androidx.navigation.NavBackStackEntry, screen: EditorSession, settings: FontSettings) {
        DisposableEffect(entry, screen) {
            currentEditor = screen
            entry.savedStateHandle.setSavedStateProvider("editor") { screen.captureState() }
            onDispose {
                if (currentEditor === screen) {
                    screen.saveForLeave()
                    currentEditor = null
                }
                entry.savedStateHandle.clearSavedStateProvider("editor")
                screen.dispose()
            }
        }
        screen.Content(settings)
    }

    @Composable
    private fun ListContent(settings: FontSettings, preview: Boolean = false) {
        val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
        val composingWithIme = inlineTask != null &&
            WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
        val listFocus = remember { FocusRequester() }
        val accessibility = LocalAccessibilityManager.current
        val pending = pendingDelete
        val pendingIds = pending?.takeIf { it.section == Section.TASKS }?.ids.orEmpty()
        val checkedTaskIds = tasks.asSequence().filter { it.task.completed && it.task.id !in pendingIds }
            .map { it.task.id }.toList()
        LaunchedEffect(pending, preview) {
            if (pending != null && !preview) {
                val timeout = accessibility?.calculateRecommendedTimeoutMillis(5000, true, true, true) ?: 5000L
                val timer = launch { delay(timeout); snackbar.currentSnackbarData?.dismiss() }
                val result = snackbar.showSnackbar("Deleted ${pending.ids.size}", "Undo",
                    withDismissAction = true, duration = SnackbarDuration.Indefinite)
                timer.cancel()
                if (pendingDelete === pending) {
                    if (result == SnackbarResult.ActionPerformed) undoDelete() else commitPendingDelete()
                }
            }
        }
        if (!preview) {
                BackHandler(selectionMode || inlineTask != null) {
                    if (inlineTask != null) closeInlineTask() else cancelSelection()
                }
        }
                Box(Modifier.fillMaxSize()) {
                 Scaffold(modifier = Modifier.onPreviewKeyEvent { handleShortcut(it.nativeKeyEvent) }
                     .focusRequester(listFocus).focusable(enabled = !preview),
                    snackbarHost = { if (!preview) SnackbarHost(snackbar) },
                    contentWindowInsets = WindowInsets.safeDrawing,
                    bottomBar = {
                        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
                            if (selectionMode) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.selected_count, selectedIds.size),
                                    Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                TextButton(onClick = ::deleteSelected, enabled = selectedIds.isNotEmpty() && !busy,
                                    modifier = Modifier.focusRing().testTag("delete-selected")) { Text(stringResource(R.string.delete_note)) }
                                TextButton(onClick = ::cancelSelection, modifier = Modifier.focusRing()) { Text(stringResource(R.string.cancel)) }
                            }
                            if (!composingWithIme) BottomSectionPanel(section, {
                                switchSection(it)
                            })
                        }
                    }, floatingActionButton = {
                        if (!composingWithIme) Column(horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (section == Section.TASKS && checkedTaskIds.isNotEmpty()) {
                                FloatingActionButton(onClick = {
                                    if (inlineTask != null && !closeInlineTask()) return@FloatingActionButton
                                    val hiddenIds = pendingDelete?.takeIf { it.section == Section.TASKS }?.ids.orEmpty()
                                    val ids = tasks.asSequence().filter { it.task.completed && it.task.id !in hiddenIds }
                                        .map { it.task.id }.toList()
                                    if (ids.isNotEmpty()) stageDelete(ids)
                                }, modifier = Modifier.focusRing().testTag("delete-checked"),
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = contentColorFor(MaterialTheme.colorScheme.secondaryContainer)) {
                                    Icon(painterResource(R.drawable.ic_delete_sweep),
                                        contentDescription = stringResource(R.string.delete_checked))
                                }
                            }
                            FloatingActionButton(onClick = { create(if (section == Section.NOTES)
                                CreationShortcut.NOTE else CreationShortcut.TASK) },
                                modifier = Modifier.focusRing().testTag("create"),
                                containerColor = MaterialTheme.colorScheme.primaryContainer) {
                                Icon(Icons.Default.Add, contentDescription = getString(
                                    if (section == Section.NOTES) R.string.add_note else R.string.add_task))
                            }
                        }
                    }) { padding ->
                    Crossfade(section, animationSpec = tween(140), label = "list-section") { displayedSection ->
                    val active = !preview && displayedSection == section
                    val visibleNotes = notes.filterNot { pending?.section == Section.NOTES && it.id in pending.ids }
                    val visibleTasks = tasks.filterNot { pending?.section == Section.TASKS && it.task.id in pending.ids }
                    val keys = if (displayedSection == Section.NOTES) visibleNotes.map { "note-${it.id}" }
                        else visibleTasks.map { "task-${it.task.id}" }
                      val scroll = if (displayedSection == Section.NOTES) noteScroll else taskScroll
                      val scrollProgress by remember(scroll) { derivedStateOf {
                          scroll.firstVisibleItemIndex.toFloat() / (scroll.layoutInfo.totalItemsCount - 1).coerceAtLeast(1)
                      } }
                      val editingId = inlineTask?.editingTaskId
                      LaunchedEffect(active, editingId, loading) {
                          // Scaffold subcomposition can still be measuring when this effect starts.
                          withFrameNanos { }
                          if (active && displayedSection == Section.TASKS && editingId != null && !loading) {
                             val index = visibleTasks.indexOfFirst { it.task.id == editingId }.coerceAtLeast(0)
                             scroll.scrollToItem(index + if (error != null) 1 else 0)
                         }
                     }
                     val requesters = remember { mutableMapOf<String, FocusRequester>() }
                      LaunchedEffect(active, keyboard, loading, focusRevision) {
                          // Scaffold may start this effect during subcomposition; wait before forcing layout.
                          withFrameNanos { }
                           if (active && !loading && inlineTask == null) {
                             // Keep a key target even when the outgoing row disappears or this section is empty.
                             listFocus.requestFocus()
                         }
                          if (active && keyboard && !loading && keys.isNotEmpty() && inlineTask == null) {
                            val target = restoreRow?.takeIf { it in keys } ?: keys.getOrNull(scroll.firstVisibleItemIndex) ?: keys.first()
                            val headerCount = (if (error != null) 1 else 0)
                            if (scroll.layoutInfo.visibleItemsInfo.none { it.key == target })
                                scroll.scrollToItem(keys.indexOf(target) + headerCount)
                            withFrameNanos { }
                            requesters[target]?.requestFocus()
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize().consumeWindowInsets(padding).testTag(if (active || preview) "items" else "outgoing-items"),
                        state = scroll,
                        contentPadding = PaddingValues(start = padding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) + 16.dp,
                            end = padding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) + 64.dp,
                            top = padding.calculateTopPadding() + 8.dp,
                            // The IME already reduces the viewport; don't reserve FAB space below the composer too.
                             bottom = padding.calculateBottomPadding() + if (inlineTask != null) 8.dp
                                 else if (displayedSection == Section.TASKS && checkedTaskIds.isNotEmpty()) 156.dp else 88.dp)) {
                        error?.let { message -> item { ErrorNotice(message) { load() } } }
                        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("loading")) }
                        if (displayedSection == Section.NOTES) {
                            if (!loading && visibleNotes.isEmpty()) item { Text("No notes yet · Alt+N or + to create", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            items(visibleNotes, key = { "note-${it.id}" }) { note ->
                                val key = "note-${note.id}"
                                val requester = remember(key) { FocusRequester() }
                                DisposableEffect(key) { requesters[key] = requester; onDispose { requesters.remove(key) } }
                                val selected = note.id in selectedIds
                                Column(Modifier.animateItem()) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).focusRequester(requester)
                                    .onFocusChanged { if (it.hasFocus && active) focusedRow = key else if (focusedRow == key) focusedRow = null }.focusRing()
                                    .testTag("note-${note.id}").semantics {
                                        if (selectionMode) this.selected = selected
                                        customActions = listOf(CustomAccessibilityAction(getString(R.string.select_note)) {
                                            if (active) { selectionMode = true; toggle(note.id) }; active
                                        })
                                    }.combinedClickable(enabled = active, onClick = { if (selectionMode) toggle(note.id) else openNote(note.id) },
                                        onLongClick = { selectionMode = true; toggle(note.id) })
                                    .padding(vertical = 12.dp)) {
                                    if (selectionMode) Checkbox(selected, { toggle(note.id) }, enabled = active,
                                        modifier = Modifier.semantics { contentDescription = getString(R.string.select_note) })
                                    Column(Modifier.weight(1f)) {
                                        NotePreview(note)
                                     }
                                }
                                  HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                }
                            }
                        } else {
                             val editor = inlineTask.takeIf { active }
                             if (editor != null && visibleTasks.none { it.task.id == editor.editingTaskId })
                                 item(key = "task-composer") { editor.Content(settings) }
                             if (!loading && visibleTasks.isEmpty() && editor == null) item { Text("No to-dos yet · Alt+T or + to create", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            items(visibleTasks, key = { "task-${it.task.id}" }) { summary ->
                                val key = "task-${summary.task.id}"
                                val requester = remember(key) { FocusRequester() }
                                DisposableEffect(key) { requesters[key] = requester; onDispose { requesters.remove(key) } }
                                 Column(Modifier.animateItem()) {
                                 if (editor?.editingTaskId == summary.task.id) {
                                     editor.Content(settings)
                                 } else {
                                 TaskCheckRow(Modifier.fillMaxWidth().heightIn(min = 64.dp).focusRequester(requester)
                                    .onFocusChanged { if (it.hasFocus && active) focusedRow = key else if (focusedRow == key) focusedRow = null }.focusRing().testTag(key)
                                    .combinedClickable(enabled = active, onClick = { openTask(summary.task.id) },
                                        onLongClick = {
                                            if (inlineTask != null && !closeInlineTask()) return@combinedClickable
                                            window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            stageDelete(listOf(summary.task.id))
                                        })
                                    .padding(vertical = 12.dp), checkbox = { alignment ->
                                    Checkbox(summary.task.completed, { completed -> mutate({ helper ->
                                        check(helper.setTaskCompleted(summary.task.id, completed) == 1)
                                    }) }, enabled = active && !busy, modifier = alignment.testTag("task-completed-${summary.task.id}").semantics {
                                        contentDescription = getString(R.string.complete_task)
                                    })
                                }) { alignment ->
                                    Column(alignment) {
                                        Text(summary.task.text, modifier = Modifier.testTag("task-label-${summary.task.id}"),
                                            style = taskLabelStyle(summary.task.completed), maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                  }
                             }
                         }
                     }
                     }
                     if (active || preview) ForwardScrollIndicator(scroll.canScrollForward,
                             scrollProgress,
                             Modifier.matchParentSize().padding(top = padding.calculateTopPadding(),
                                 bottom = padding.calculateBottomPadding()))
                     }
                         }
                     }
                 TopRightFontSettings { settingsOpen = true }
                }
                if (settingsOpen && !preview) FontSettingsDialog(settings, onKeyboardShortcuts = {
                    settingsOpen = false; requestShowKeyboardShortcuts()
                }) { settingsOpen = false }
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        QuickCapture.from(intent)?.let {
            pendingCaptures = pendingCaptures + it
            return
        }
        setIntent(intent)
        if (intent.hasExtra(EXTRA_SECTION)) {
            switchSection(Section.entries.getOrElse(runCatching { intent.getIntExtra(EXTRA_SECTION, 0) }.getOrDefault(0)) { Section.NOTES })
            cancelSelection()
        }
    }
    override fun onResume() {
        super.onResume(); resumed = true
        creation.consume()?.onSuccess { openNote(it) }?.onFailure { error = getString(R.string.write_failed) }
        load()
    }
    override fun onPause() { currentEditor?.saveForLeave(); resumed = false; creation.abandon(); generation++; super.onPause() }
    override fun onStop() { if (!isChangingConfigurations) commitPendingDelete(); super.onStop() }
    override fun onDestroy() { NotesDataEvents.listeners.remove(dataChanged); inlineTask?.dispose(); executor.shutdown(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        if (inlineTask != null) outState.putBundle("inline_task", inlineTask!!.captureState())
        else currentEditor?.captureState()?.let { state -> navController.currentBackStackEntry?.savedStateHandle?.set("editor", state) }
        outState.putInt("section", section.ordinal)
        outState.putBoolean("selection_mode", selectionMode)
        outState.putLongArray("selected_ids", selectedIds.toLongArray())
        if (pendingCaptures.isNotEmpty()) outState.putParcelableArrayList("captures", ArrayList(pendingCaptures.map { it.save() }))
        pendingDelete?.let {
            outState.putInt("pending_delete_section", it.section.ordinal)
            outState.putLongArray("pending_delete_ids", it.ids.toLongArray())
        }
        super.onSaveInstanceState(outState)
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleShortcut(event) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        handleShortcut(event) || super.onKeyUp(keyCode, event)

    private fun handleShortcut(event: KeyEvent): Boolean {
        // Swallow the matching release so Space cannot also activate a newly focused tab.
        if (event.action == KeyEvent.ACTION_UP) return shortcutKeyUps.remove(event.keyCode)
        if (event.repeatCount > 0 && event.keyCode in shortcutKeyUps) return true
        if (::navController.isInitialized && navController.currentDestination?.route != "list")
            return currentEditor?.handleShortcut(event) ?: false
        if (settingsOpen) return false
        if (inlineTask?.handleShortcut(event) == true) { shortcutKeyUps.add(event.keyCode); return true }
        val textFocused = inlineTask?.textFocused == true || currentFocus?.onCheckIsTextEditor() == true
        val action = event.creationShortcut(textFocused)
        if (action != null) {
            create(if (action == CreationShortcut.NOTE && event.isAltPressed && section == Section.TASKS)
                CreationShortcut.TASK else action)
            shortcutKeyUps.add(event.keyCode); return true
        }
        when (event.listAction(textFocused)) {
            ListAction.SwitchSection -> switchSection(if (section == Section.NOTES) Section.TASKS else Section.NOTES)
            ListAction.Delete -> {
                if (selectionMode && selectedIds.isNotEmpty()) deleteSelected()
                else {
                    val prefix = if (section == Section.NOTES) "note-" else "task-"
                    val id = focusedRow?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toLongOrNull() ?: return false
                    stageDelete(listOf(id))
                }
            }
            ListAction.ToggleDone -> {
                if (section != Section.TASKS) return false
                val id = focusedRow?.removePrefix("task-")?.toLongOrNull() ?: return false
                val task = tasks.firstOrNull { it.task.id == id }?.task ?: return false
                mutate({ check(it.setTaskCompleted(id, !task.completed) == 1) })
            }
            ListAction.CancelSelection -> { if (!selectionMode) return false; cancelSelection() }
            ListAction.Undo -> { if (pendingDelete == null) return false; undoDelete() }
            null -> return false
        }
        shortcutKeyUps.add(event.keyCode)
        return true
    }
    private fun switchSection(next: Section) {
        if (inlineTask != null && !closeInlineTask()) return
        section = next; focusedRow = null; restoreRow = null; focusRevision++; cancelSelection()
    }
    private fun toggle(id: Long) { selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }
    private fun cancelSelection() { selectionMode = false; selectedIds = emptySet() }
    private fun load() {
        val token = ++generation
        loading = true
        executor.execute {
            val result = runCatching { DatabaseHelper(applicationContext).use { it.getNotePreviews() to it.getTaskSummaries() } }
            runOnUiThread {
                if (!isDestroyed && !isFinishing && resumed && token == generation) {
                    loading = false
                    result.onSuccess { (n, t) ->
                        notes = n; tasks = t; error = null
                        // A real stop may have committed after state capture; restore only surviving rows.
                        pendingDelete?.let { pending ->
                            val existing = if (pending.section == Section.NOTES) n.map { it.id }.toSet()
                                else t.map { it.task.id }.toSet()
                            val ids = pending.ids.filter { it in existing }
                            if (ids != pending.ids) pendingDelete = ids.takeIf { it.isNotEmpty() }?.let { PendingDelete(pending.section, it) }
                        }
                        selectedIds = selectedIds.intersect(n.map { it.id }.toSet())
                    }.onFailure { error = getString(R.string.load_failed) }
                }
            }
        }
    }
    private fun mutate(action: (DatabaseHelper) -> Unit, success: () -> Unit = {}) {
        if (busy || creation.pending) return
        busy = true
        executor.execute {
            val result = runCatching { DatabaseHelper(applicationContext).use(action) }
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    busy = false
                    result.onSuccess { success(); if (resumed) load() }
                        .onFailure { error = getString(R.string.write_failed) }
                }
            }
        }
    }
    private fun deleteSelected() {
        stageDelete(selectedIds.toList())
    }
    private fun stageDelete(ids: List<Long>) {
        if (busy || creation.pending || ids.isEmpty() || !commitPendingDelete()) return
        val ordered = if (section == Section.NOTES) notes.map { it.id } else tasks.map { it.task.id }
        val index = ordered.indexOfFirst { it in ids }.coerceAtLeast(0)
        val remaining = ordered.filterNot { it in ids }
        restoreRow = remaining.getOrNull(index.coerceAtMost(remaining.lastIndex))?.let {
            if (section == Section.NOTES) "note-$it" else "task-$it"
        }
        focusedRow = null
        pendingDelete = PendingDelete(section, ids)
        cancelSelection(); focusRevision++
    }
    private fun undoDelete() {
        val pending = pendingDelete ?: return
        restoreRow = (if (pending.section == Section.NOTES) "note-" else "task-") + pending.ids.first()
        pendingDelete = null; snackbar.currentSnackbarData?.dismiss(); focusRevision++
    }
    private fun commitPendingDelete(): Boolean {
        val pending = pendingDelete ?: return true
        // Serialize with list writes and finish before leaving; failed deletes restore the UI.
        val result = runCatching { executor.submit<Unit> {
            DatabaseHelper(applicationContext).use { helper ->
                if (pending.section == Section.NOTES) check(helper.deleteNotes(pending.ids) == pending.ids.size)
                else check(helper.deleteTasks(pending.ids) == pending.ids.size)
            }
        }.get() }
        result.onSuccess {
            if (pending.section == Section.NOTES) notes = notes.filterNot { it.id in pending.ids }
            else tasks = tasks.filterNot { it.task.id in pending.ids }
            generation++
            loading = false
        }.onFailure { error = getString(R.string.write_failed) }
        pendingDelete = null; snackbar.currentSnackbarData?.dismiss()
        return result.isSuccess
    }
    private fun create(action: CreationShortcut) {
        if (busy || creation.pending) return
        if (inlineTask != null && !closeInlineTask()) return
        if (!commitPendingDelete()) return
        section = if (action == CreationShortcut.NOTE) Section.NOTES else Section.TASKS
        cancelSelection()
        if (action == CreationShortcut.TASK) openTask(null)
        else creation.begin()
    }
    private fun openCapture() {
        val capture = pendingCaptures.firstOrNull() ?: return
        if (currentEditor?.saveForLeave() == false) {
            currentEditor?.showError(getString(R.string.capture_open_failed))
            return
        }
        if (!commitPendingDelete()) return
        if (inlineTask != null && !closeInlineTask()) return
        // A pending list insertion owns its row until abandoned; do not open it over a capture.
        creation.abandon()
        if (capture.action == QuickCapture.NEW_TODO) {
            pendingCaptures = pendingCaptures.drop(1); settingsOpen = false
            navController.popBackStack("list", false)
            openTask(null, capture.text)
            return
        }
        runCatching {
            when (capture.action) {
                QuickCapture.NEW_NOTE -> DatabaseHelper(this).use { "note/${it.insertNote("", "")}" }
                else -> "note-picker"
            }
        }.onSuccess { route ->
            pendingCaptures = pendingCaptures.drop(1)
            section = Section.NOTES
            settingsOpen = false; cancelSelection()
            navController.navigate(route) { popUpTo("list"); launchSingleTop = false }
            navController.currentBackStackEntry?.savedStateHandle?.set("capture_text", capture.text)
        }.onFailure { error = getString(R.string.write_failed) }
    }
    private fun openNote(id: Long) {
        if (!resumed || creation.pending) return
        if (inlineTask != null && !closeInlineTask()) return
        if (!commitPendingDelete()) return
        restoreRow = "note-$id"
        navController.navigate("note/$id")
    }
    internal fun openTask(id: Long?, text: String? = null) {
        if (!resumed || creation.pending) return
        if (!commitPendingDelete()) return
        if (inlineTask != null && !closeInlineTask()) return
        section = Section.TASKS; cancelSelection()
        restoreRow = id?.let { "task-$it" }
        inlineTask = TaskScreen(this, null, id, ::closeInlineTask, ::load, text)
        currentEditor = inlineTask
    }

    private fun closeInlineTask(): Boolean {
        val editor = inlineTask ?: return true
        if (!editor.saveForLeave()) return false
        if (currentEditor === editor) currentEditor = null
        inlineTask = null; editor.dispose(); focusRevision++; load()
        return true
    }

    internal fun closeEditor() {
        if (pendingCaptures.isNotEmpty()) openCapture()
        else if (inlineTask != null) closeInlineTask()
        else if (currentEditor?.saveForLeave() != false) navController.popBackStack()
    }

    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        fun shortcut(label: String, key: Int, modifiers: Int = 0) = KeyboardShortcutInfo(label, key, modifiers)
        val alt = KeyEvent.META_ALT_ON; val shift = KeyEvent.META_SHIFT_ON; val ctrl = KeyEvent.META_CTRL_ON
        data.add(KeyboardShortcutGroup("Lists", listOf(
            shortcut("New note / to-do", KeyEvent.KEYCODE_N, alt), shortcut("New note", KeyEvent.KEYCODE_N, shift),
            shortcut("New to-do", KeyEvent.KEYCODE_T, alt), shortcut("New to-do", KeyEvent.KEYCODE_T, shift),
             shortcut("Switch Notes / To-dos (Shift+Space / Alt+Space)", KeyEvent.KEYCODE_SPACE, shift),
             shortcut("Switch Notes / To-dos", KeyEvent.KEYCODE_SPACE, alt), shortcut("Delete row", KeyEvent.KEYCODE_DEL),
            shortcut("Toggle done", KeyEvent.KEYCODE_D, ctrl), shortcut("Cancel selection", KeyEvent.KEYCODE_ESCAPE),
            shortcut("Undo delete", KeyEvent.KEYCODE_Z, ctrl))))
        data.add(KeyboardShortcutGroup("Editors", listOf(
            shortcut("Next item / to-do", KeyEvent.KEYCODE_ENTER), shortcut("Newline", KeyEvent.KEYCODE_ENTER, shift),
            shortcut("Toggle checkbox", KeyEvent.KEYCODE_D, ctrl), shortcut("Save", KeyEvent.KEYCODE_S, ctrl),
            shortcut("Save and close", KeyEvent.KEYCODE_ESCAPE), shortcut("Save and close", KeyEvent.KEYCODE_ENTER, alt))))
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
    }

    private fun replaceEditor(action: CreationShortcut) {
        if (currentEditor?.saveForLeave() == false) return
        val captureText = navController.currentBackStackEntry?.takeIf { it.destination.route == "note-picker" }
            ?.savedStateHandle?.get<String>("capture_text")
        if (action == CreationShortcut.TASK) {
            navController.popBackStack("list", false)
            openTask(null, captureText)
            return
        }
        runCatching {
            "note/${DatabaseHelper(this).use { it.insertNote("", "") }}"
        }.onSuccess { route ->
            section = Section.NOTES
            navController.navigate(route) { popUpTo(navController.currentDestination!!.id) { inclusive = true } }
            captureText?.let { navController.currentBackStackEntry?.savedStateHandle?.set("capture_text", it) }
        }.onFailure { error = getString(R.string.write_failed) }
    }
}
