package com.openminis.app.ui.scheduled

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openminis.app.R
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledRun
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.ui.settings.SettingsSwitch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-android-scheduled-tasks-design] Backing VM for ScheduledTasksScreen
 * and ScheduledTaskEditScreen. Thin wrapper around [ScheduledTaskManager]
 * exposing a reactive [tasks] flow + suspend functions for the editor.
 */
class ScheduledTasksViewModel(private val appContext: Context) : ViewModel() {

    private val manager = ScheduledTaskManager(appContext)
    private val app get() = appContext as com.openminis.app.MinisApp

    /**
     * [T-android-scheduled-lateinit-crash-156] True when the Application's
     * repositories are actually assigned.
     *
     * Every `app.<repository>` read below is a lateinit getter that throws
     * UninitializedPropertyAccessException if MinisApp.onCreate early-returned
     * (safe-mode) or aborted partway. The screens backing this ViewModel can be
     * composed in exactly that state — a scheduled-task notification tap
     * restores the task into an Activity while the Application behind it is
     * permanently half-built — so the reads must not be unconditional.
     *
     * Callers degrade to empty lists / nulls, which renders an empty picker
     * instead of taking the process down.
     */
    private fun ready(): Boolean =
        (appContext as? com.openminis.app.MinisApp)?.subsystemsReady() == true

    /** Exposed for the editor's ModelPickerSheet — read-only access to the
     *  same ProviderRepository the chat screen uses.
     *
     *  [T-android-scheduled-lateinit-crash-156] Nullable, behind the same
     *  [ready] gate as every other `app.<repository>` read in this file. This
     *  accessor was the one that read the lateinit unconditionally, so the very
     *  half-built-Application case the rest of the class defends against
     *  (a scheduled-task notification tap restoring the editor) crashed here
     *  instead. Callers render the picker's empty state, matching how
     *  [listSessions] / [listModels] degrade. */
    val providerRepository get() = if (ready()) app.providerRepository else null

    val tasks: StateFlow<List<ScheduledTask>> = manager.store().observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // [T-android-sched-badge-toggle] Home clock-icon count badge on/off.
    val showHomeBadge: StateFlow<Boolean> = manager.store().observeShowHomeBadge()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), manager.store().showHomeBadge())

    fun setShowHomeBadge(show: Boolean) = manager.store().setShowHomeBadge(show)

    // ── [T-android-scheduled-tasks-full] Picker data for the editor ──

    /** Lightweight session row for the Follow-up / Re-run session picker. */
    data class SessionOption(val id: String, val title: String, val updatedAt: Long)

    /** A user message in a session, for the Re-run message picker. */
    data class MessageOption(val id: String, val preview: String, val createdAt: Long)

    /** A selectable model entry for the optional model override. */
    data class ModelOption(val entryId: String, val modelId: String, val displayName: String, val providerLabel: String)

    suspend fun listSessions(limit: Int = 100): List<SessionOption> = withContext(Dispatchers.IO) {
        if (!ready()) return@withContext emptyList()
        // [T-child-session-leak] A scheduled task's target picker is a
        // user-facing session list: hide agent child sessions.
        val childIds = app.chatRepository.dao.listSessions().filter { it.isChild }.map { it.id }.toSet()
        app.chatRepository.querySessionsMeta(
            sessionIds = null, keywords = null, limit = limit, startMs = null, endMs = null,
        ).filter { it.id !in childIds }.map {
            SessionOption(it.id, it.title?.ifBlank { null } ?: "Untitled", it.lastActive)
        }
    }

    suspend fun listUserMessages(sessionId: String): List<MessageOption> = withContext(Dispatchers.IO) {
        if (!ready()) return@withContext emptyList()
        app.chatRepository.loadMessages(sessionId)
            .filter { it.role == "user" }
            .map { MessageOption(it.id, extractPreview(it.partsJson), it.createdAt) }
    }

    /** Best-effort human-readable label for a `model_binding` JSON string —
     *  group name for {"type":"group"}, model display name for
     *  {"type":"entry"}, null on parse failure or unknown ids. Mirrors the
     *  chip text the chat screen renders for the same binding. */
    fun describeBinding(json: String?): String? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val o = org.json.JSONObject(json)
            when (o.optString("type")) {
                "group" -> {
                    val gid = o.optString("groupId").takeIf { it.isNotEmpty() } ?: return@runCatching null
                    app.providerRepository.group(gid)?.name
                }
                "entry" -> {
                    val eid = o.optString("entryId").takeIf { it.isNotEmpty() } ?: return@runCatching null
                    app.providerRepository.config.value.modelEntries.firstOrNull { it.id == eid }?.model?.displayName
                }
                else -> null
            }
        }.getOrNull()
    }

    fun listModels(): List<ModelOption> {
        if (!ready()) return emptyList()
        return app.providerRepository.allVisibleEntries().mapNotNull { entry ->
            val instance = app.providerRepository.instance(entry.providerInstanceId) ?: return@mapNotNull null
            ModelOption(
                entryId = entry.id,
                modelId = entry.baseModel.id,
                displayName = entry.model.displayName,
                providerLabel = instance.label.ifEmpty { entry.model.provider },
            )
        }
    }

    private fun extractPreview(partsJson: String): String {
        val text = runCatching {
            val arr = org.json.JSONArray(partsJson)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("type") == "text") sb.append(o.optString("value", ""))
            }
            sb.toString()
        }.getOrNull() ?: ""
        // [T-md-inline-html] <br> → space, whitelisted style tags out (code untouched).
        return com.openminis.app.ui.markdown.InlineHtmlTags.stripForPreview(text)
            .trim().replace('\n', ' ').take(80).ifBlank { "(empty)" }
    }

    private val _runNowState = MutableStateFlow<RunNowState?>(null)
    val runNowState: StateFlow<RunNowState?> = _runNowState.asStateFlow()

    fun setEnabled(taskId: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { manager.setEnabled(taskId, enabled) }
    }

    fun delete(taskId: String) {
        viewModelScope.launch(Dispatchers.IO) { manager.delete(taskId) }
    }

    suspend fun get(taskId: String): ScheduledTask? = withContext(Dispatchers.IO) {
        manager.get(taskId)
    }

    suspend fun upsert(task: ScheduledTask, isNew: Boolean): ScheduledTask =
        withContext(Dispatchers.IO) {
            if (isNew) manager.create(task) else manager.update(task)
        }

    /**
     * "Run now" button on the edit screen. Fires the task immediately without
     * touching the schedule.
     *
     * [T-android-scheduled-tasks-full] Async by design: we call the runner with
     * waitForCompletion=false, so this returns as soon as the session is
     * resolved and the prompt is dispatched (status STARTED). The agent keeps
     * running in the background; the completion notification fires later. The
     * UI shows a brief loading spinner that flips to "task started" — it does
     * NOT block on the whole agent loop. STARTED carries the session id so the
     * user can jump straight into the (now-running) chat.
     */
    fun runNow(task: ScheduledTask) {
        viewModelScope.launch(Dispatchers.IO) {
            _runNowState.value = RunNowState(taskId = task.id, status = RunStatus.RUNNING)
            val sid = runCatching {
                com.openminis.app.scheduled.ScheduledAgentRunner.run(
                    appContext, task, waitForCompletion = false,
                )
            }.getOrNull()
            _runNowState.value = RunNowState(
                taskId = task.id,
                status = if (sid != null) RunStatus.STARTED else RunStatus.FAILED,
                sessionId = sid,
            )
        }
    }

    fun clearRunNowState() { _runNowState.value = null }

    enum class RunStatus { RUNNING, STARTED, FAILED }
    data class RunNowState(
        val taskId: String,
        val status: RunStatus,
        val sessionId: String? = null,
    )

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ScheduledTasksViewModel(context.applicationContext) as T
                }
            }
    }
}

// ─── Merged from ScheduledTaskRunsScreen.kt ──────────────────────────────────────────────────

/**
 * [T-android-scheduled-tasks-run-records] Execution log for one scheduled
 * task. Lists each recorded run newest-first; a run that produced a chat
 * (non-null sessionId) is tappable → opens that chat. Backed by the live
 * tasks flow so new runs appear without re-entering the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTaskRunsScreen(
    taskId: String,
    onBack: () -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
) {
    val context = LocalContext.current
    val vm: ScheduledTasksViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = ScheduledTasksViewModel.factory(context),
    )
    val tasks by vm.tasks.collectAsState()
    val task = tasks.firstOrNull { it.id == taskId }
    val runs = task?.runHistory ?: emptyList()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.scheduled_task_runs_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        )
                        if (task != null && task.label.isNotBlank()) {
                            Text(
                                task.label,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (runs.isEmpty()) {
            EmptyRuns(padding)
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(runs, key = { it.firedAt }) { run ->
                RunRow(run = run, onOpenSession = onOpenSession)
            }
        }
    }
}

@Composable
private fun EmptyRuns(padding: PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.History,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.scheduled_task_runs_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RunRow(run: ScheduledRun, onOpenSession: (String) -> Unit) {
    val tappable = run.sessionId != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (tappable) Modifier.clickable { onOpenSession(run.sessionId!!) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (run.ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = null,
            tint = if (run.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = formatRunTime(run.firedAt),
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
            val preview = run.preview
            if (!preview.isNullOrBlank()) {
                Text(
                    text = preview,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (tappable) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.scheduled_task_runs_open_session),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun formatRunTime(ms: Long): String =
    SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(Date(ms))

// ─── Merged from ScheduledTasksScreen.kt ──────────────────────────────────────────────────

/**
 * [T-android-scheduled-tasks-design / T-android-scheduled-tasks-run-records]
 * List + manage scheduled tasks. Entry point from SessionListScreen's
 * TopAppBar; tap a row to edit, FAB to create, switch to enable/disable.
 * Long-press opens a menu: Edit / Run records / Delete (with confirm).
 * The row no longer shows a result preview — execution history lives in
 * the Run records screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTasksScreen(
    onBack: () -> Unit,
    onEditTask: (taskId: String?) -> Unit,
    onViewRuns: (taskId: String) -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
) {
    val context = LocalContext.current
    val vm: ScheduledTasksViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = ScheduledTasksViewModel.factory(context),
    )
    val tasks by vm.tasks.collectAsState()
    var pendingDelete by remember { mutableStateOf<ScheduledTask?>(null) }
    val showHomeBadge by vm.showHomeBadge.collectAsState()
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.scheduled_tasks_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                // [T-android-sched-badge-toggle] A menu rather than a row in the
                // list: the list holds task cards and is replaced by the empty
                // state when there are none, so a row would vanish exactly when
                // the count is zero.
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.model_group_detail_more_options),
                            )
                        }
                        com.openminis.app.ui.components.MinisMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.scheduled_tasks_show_home_badge)) },
                                onClick = { vm.setShowHomeBadge(!showHomeBadge) },
                                trailingIcon = {
                                    SettingsSwitch(
                                        checked = showHomeBadge,
                                        onCheckedChange = { vm.setShowHomeBadge(it) },
                                    )
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEditTask(null) }, shape = CircleShape) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.scheduled_task_new))
            }
        },
    ) { padding ->
        if (tasks.isEmpty()) {
            EmptyState(padding)
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(tasks, key = { it.id }) { task ->
                ScheduledTaskRow(
                    task = task,
                    onClick = { onEditTask(task.id) },
                    onToggle = { vm.setEnabled(task.id, it) },
                    onEdit = { onEditTask(task.id) },
                    onViewRuns = { onViewRuns(task.id) },
                    onDelete = { pendingDelete = task },
                )
            }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.scheduled_task_delete_title)) },
            text = { Text(stringResource(R.string.scheduled_task_delete_body, toDelete.label)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.delete(toDelete.id)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.scheduled_task_delete_confirm))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Schedule,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.scheduled_tasks_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScheduledTaskRow(
    task: ScheduledTask,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onViewRuns: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember(task.id) { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = onClick, onLongClick = { menuExpanded = true })
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (task.enabled)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = if (task.enabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.label.ifBlank { task.prompt.take(40) },
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatScheduleSummary(task),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            SettingsSwitch(checked = task.enabled, onCheckedChange = onToggle)
        }

        // [T-android-scheduled-tasks-run-records] Long-press menu: Edit / Run
        // records / Delete (delete is confirmed by the caller's dialog).
        com.openminis.app.ui.components.MinisMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.scheduled_task_menu_edit)) },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = { menuExpanded = false; onEdit() },
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.scheduled_task_menu_runs)) },
                leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                onClick = { menuExpanded = false; onViewRuns() },
            )
            androidx.compose.material3.DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.scheduled_task_menu_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingIcon = {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = { menuExpanded = false; onDelete() },
            )
        }
    }
}

internal fun formatScheduleSummary(task: ScheduledTask): String {
    val nextSuffix = task.nextTriggerMs()?.let {
        " · next ${SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(it))}"
    } ?: ""
    // [T-android-scheduled-triggers] Tasks the assistant created with
    // minis-scheduled's relative triggers, described in the CLI's own terms.
    when (task.triggerKind) {
        com.openminis.app.scheduled.ScheduledTriggerKind.AFTER ->
            return "Once after ${com.openminis.app.scheduled.ScheduledTaskDetailModel.formatDuration(task.delaySec ?: 0L)}$nextSuffix"
        com.openminis.app.scheduled.ScheduledTriggerKind.INTERVAL -> {
            val every = com.openminis.app.scheduled.ScheduledTaskDetailModel.formatDuration(task.intervalSec ?: 0L)
            val count = task.maxFires?.let { " ×$it (${task.remainingFires} left)" } ?: ""
            return "Every $every$count$nextSuffix"
        }
        com.openminis.app.scheduled.ScheduledTriggerKind.ON_COMPLETION ->
            return "After ${task.onCompletionOf.orEmpty().take(8)} finishes"
        com.openminis.app.scheduled.ScheduledTriggerKind.CALENDAR -> Unit
    }
    val time = "%02d:%02d".format(task.timeOfDayHour, task.timeOfDayMinute)
    val repeat = when (task.repeatMode) {
        ScheduledRepeatMode.ONCE -> "Once"
        ScheduledRepeatMode.DAILY -> "Daily"
        ScheduledRepeatMode.WEEKDAYS -> "Weekdays"
        ScheduledRepeatMode.CUSTOM -> {
            val days = task.customDays.sorted().joinToString(",") { dowShort(it) }
            if (days.isBlank()) "Custom" else days
        }
    }
    val next = task.nextTriggerMs()?.let {
        val sdf = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
        " · next ${sdf.format(Date(it))}"
    } ?: ""
    return "$repeat $time$next"
}

private fun dowShort(dow: Int): String = when (dow) {
    java.util.Calendar.SUNDAY -> "Sun"
    java.util.Calendar.MONDAY -> "Mon"
    java.util.Calendar.TUESDAY -> "Tue"
    java.util.Calendar.WEDNESDAY -> "Wed"
    java.util.Calendar.THURSDAY -> "Thu"
    java.util.Calendar.FRIDAY -> "Fri"
    java.util.Calendar.SATURDAY -> "Sat"
    else -> ""
}
