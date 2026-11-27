package com.azizjon.notes.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azizjon.notes.R
import com.azizjon.notes.life.SyncStatus
import com.azizjon.notes.reminders.Event
import com.azizjon.notes.reminders.Item
import com.azizjon.notes.reminders.ItemKind
import com.azizjon.notes.reminders.ReminderNotifications
import com.azizjon.notes.reminders.ReminderPlan
import com.azizjon.notes.reminders.Upcoming
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The two tabs at the bottom of Today and the notes list. */
enum class MainTab { TODAY, NOTES, PEOPLE }

@Composable
fun MainTabs(selected: MainTab, onSelect: (MainTab) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = selected == MainTab.TODAY,
            onClick = { onSelect(MainTab.TODAY) },
            icon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
            label = { Text("Today") },
        )
        NavigationBarItem(
            selected = selected == MainTab.NOTES,
            onClick = { onSelect(MainTab.NOTES) },
            icon = { Icon(painterResource(R.drawable.ic_all_notes), contentDescription = null) },
            label = { Text("Notes") },
        )
        NavigationBarItem(
            selected = selected == MainTab.PEOPLE,
            onClick = { onSelect(MainTab.PEOPLE) },
            icon = { Icon(Icons.Filled.Person, contentDescription = null) },
            label = { Text("People") },
        )
    }
}

/**
 * Today: what the morning reminder lists (today's events and tasks, late tasks, questions, and the
 * next 7 days' events), live from the life repo's `reminders/upcoming.md`. Ticking, skipping,
 * moving, and answering change that file, one commit each.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    sync: SyncStatus,
    onSync: () -> Unit,
    onOpenList: () -> Unit,
    onOpenSettings: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var canPost by remember { mutableStateOf(ReminderNotifications.canPost(context)) }
    var answering by remember { mutableStateOf<Pair<Upcoming, Item>?>(null) }
    var moving by remember { mutableStateOf<Pair<Upcoming, Item>?>(null) }
    var openEventLine by rememberSaveable { mutableStateOf<Int?>(null) }

    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        canPost = ReminderNotifications.canPost(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            val result = snackbar.showSnackbar(
                message.text,
                actionLabel = if (message.undo != null) "Undo" else null,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) message.undo?.invoke()
        }
    }

    val ready = state as? TodayState.Ready
    val actions = ItemActions(
        onDone = { up, item -> viewModel.done(up, item) },
        onSkip = { up, item -> viewModel.skip(up, item) },
        onMoveTomorrow = { up, item, today -> viewModel.move(up, item, today.plusDays(1)) },
        onMove = { up, item -> moving = up to item },
        onAnswer = { up, item -> answering = up to item },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Today")
                        Text(
                            (ready?.day ?: LocalDate.now()).format(LONG_DAY),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(enabled = !sync.running, onClick = onSync) {
                        if (sync.running) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Sync now")
                        }
                    }
                    IconButton(onClick = onOpenList) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Open the whole list")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val s = state) {
            TodayState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            TodayState.NoList -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "This repo has no reminder list. Today shows the list in reminders/upcoming.md.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is TodayState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
            ) {
                sync.error?.let { item { SyncErrorBanner(it, onOpenSettings) } }
                if (!canPost && viewModel.remindersOn) item { NotificationsCard(context) { canPost = ReminderNotifications.canPost(context) } }
                if (s.clash) item { ClashCard(onOpenList) }
                viewModel.warnings.takeIf { it.isNotEmpty() }?.let { warnings -> item { WarningsCard(warnings) } }
                todayContent(s, actions, onOpenEvent = { openEventLine = it.line }, onToggleLate = viewModel::toggleAllLate)
            }
        }
    }

    // The sheet of one event, with every open item it still has.
    val event = ready?.up?.events?.firstOrNull { it.line == openEventLine }
    if (ready != null && event != null) {
        ModalBottomSheet(onDismissRequest = { openEventLine = null }) {
            EventSheet(ready.up, event, ready.day, actions)
        }
    }

    answering?.let { (up, item) ->
        AnswerDialog(
            question = ReminderPlan.plainText(item),
            onSave = { answer ->
                viewModel.answer(up, item, answer)
                answering = null
            },
            onDismiss = { answering = null },
        )
    }

    moving?.let { (up, item) ->
        MoveDialog(
            from = item.date ?: LocalDate.now(),
            onMove = { day ->
                viewModel.move(up, item, day)
                moving = null
            },
            onDismiss = { moving = null },
        )
    }
}

/** What each item's checkbox and menu do. */
private class ItemActions(
    val onDone: (Upcoming, Item) -> Unit,
    val onSkip: (Upcoming, Item) -> Unit,
    val onMoveTomorrow: (Upcoming, Item, LocalDate) -> Unit,
    val onMove: (Upcoming, Item) -> Unit,
    val onAnswer: (Upcoming, Item) -> Unit,
)

private fun LazyListScope.todayContent(
    s: TodayState.Ready,
    actions: ItemActions,
    onOpenEvent: (Event) -> Unit,
    onToggleLate: () -> Unit,
) {
    val d = s.digest
    section("Today")
    if (d.eventsToday.isEmpty() && d.today.isEmpty()) {
        item { Hint("Nothing due today.") }
    }
    items(d.eventsToday, key = { "event-${it.line}" }) { ev -> EventRow(ev, ReminderPlan.eventLine(ev), onClick = { onOpenEvent(ev) }) }
    items(d.today, key = { "today-${it.line}" }) { item -> ItemRow(s.up, item, s.day, actions, due = false) }

    val late = s.allLate ?: d.overdue
    if (late.isNotEmpty() || d.hidden > 0) {
        section("Still open")
        items(late, key = { "late-${it.line}" }) { item -> ItemRow(s.up, item, s.day, actions, due = true) }
        if (d.hidden > 0) {
            item {
                TextButton(onClick = onToggleLate, modifier = Modifier.padding(start = 8.dp)) {
                    Text(if (s.allLate == null) "Show ${d.hidden} older" else "Show only this week's")
                }
            }
        }
    }

    if (d.questions.isNotEmpty()) {
        section("Questions")
        items(d.questions, key = { "ask-${it.line}" }) { item -> ItemRow(s.up, item, s.day, actions, due = false) }
    }

    if (d.coming.isNotEmpty()) {
        section("Coming up")
        items(d.coming, key = { "coming-${it.line}" }) { ev ->
            val title = "${ReminderPlan.fmtDay(ev.date)}${ev.time?.let { " $it" } ?: ""} · ${ev.title}"
            EventRow(ev, title, onClick = { onOpenEvent(ev) })
        }
    }
}

private fun LazyListScope.section(title: String) {
    item(key = "section-$title") {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun EventRow(ev: Event, title: String, onClick: () -> Unit) {
    val open = ev.items.count { it.open }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text("📅 $title", style = MaterialTheme.typography.bodyLarge)
        val where = ev.meta["where"].orEmpty().trim()
        val note = listOfNotNull(
            where.takeIf { it.isNotEmpty() && it !in title },
            if (open > 0) "$open open" else null,
        ).joinToString(" · ")
        if (note.isNotEmpty()) {
            Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ItemRow(up: Upcoming, item: Item, today: LocalDate, actions: ItemActions, due: Boolean, showEvent: Boolean = true) {
    var menu by remember { mutableStateOf(false) }
    val isAsk = item.kind == ItemKind.ASK
    val note = listOfNotNull(
        item.event?.title?.takeIf { showEvent },
        item.date?.takeIf { due || it != today }?.let { "due ${ReminderPlan.fmtWhen(it, today)}" },
    ).joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = isAsk && item.open) { actions.onAnswer(up, item) }
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = item.mark == 'x',
            enabled = item.open,
            onCheckedChange = { if (item.open) actions.onDone(up, item) },
        )
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(
                ReminderPlan.plainText(item),
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (item.mark == '-') TextDecoration.LineThrough else null,
            )
            if (note.isNotEmpty()) {
                Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (item.open) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More for this item")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (isAsk) {
                        DropdownMenuItem(text = { Text("Answer…") }, onClick = { menu = false; actions.onAnswer(up, item) })
                    }
                    DropdownMenuItem(text = { Text("Skip") }, onClick = { menu = false; actions.onSkip(up, item) })
                    DropdownMenuItem(text = { Text("Move to tomorrow") }, onClick = { menu = false; actions.onMoveTomorrow(up, item, today) })
                    DropdownMenuItem(text = { Text("Move to another day…") }, onClick = { menu = false; actions.onMove(up, item) })
                }
            }
        }
    }
}

@Composable
private fun EventSheet(up: Upcoming, ev: Event, today: LocalDate, actions: ItemActions) {
    val open = ev.items.filter { it.open }.sortedBy { it.date }
    LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(ev.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    "${ReminderPlan.fmtDay(ev.date)}${ev.time?.let { " $it" } ?: ""}" +
                        (ev.meta["where"]?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (open.isEmpty()) item { Hint("Nothing left to do for it.") }
        items(open, key = { "sheet-${it.line}" }) { item ->
            ItemRow(up, item, today, actions, due = item.date?.isBefore(today) == true, showEvent = false)
        }
    }
}

@Composable
private fun AnswerDialog(question: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var answer by rememberSaveable { mutableStateOf("") }
    var talking by remember { mutableStateOf(false) }
    val startTalking = rememberMicStarter { talking = true }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(question) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    label = { Text("Your answer") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = startTalking) {
                        Icon(painterResource(R.drawable.ic_mic), contentDescription = "Say the answer")
                    }
                    Text(
                        "It goes to the list's inbox, and the next Claude session puts it where it belongs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(enabled = answer.isNotBlank(), onClick = { onSave(answer) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (talking) {
        VoiceDialog(
            onText = { text -> answer = listOf(answer.trim(), text.trim()).filter { it.isNotEmpty() }.joinToString(" ") },
            onClose = { talking = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveDialog(from: LocalDate, onMove: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val start = maxOf(from, LocalDate.now()).plusDays(1)
    val picker = rememberDatePickerState(initialSelectedDateMillis = start.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = picker.selectedDateMillis != null,
                onClick = {
                    picker.selectedDateMillis?.let { onMove(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                },
            ) { Text("Move") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = picker)
    }
}

@Composable
private fun NotificationsCard(context: Context, onChanged: () -> Unit) {
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true
        onChanged()
    }
    InfoCard(
        title = "Turn on notifications",
        text = "Notes reminds you at 8:00 of what is due, and at 20:00 the evening before an event. It needs " +
            "your permission to show notifications.",
    ) {
        OutlinedButton(onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !asked) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }) { Text("Allow notifications") }
    }
}

@Composable
private fun ClashCard(onOpenList: () -> Unit) {
    InfoCard(
        title = "The list was changed on the phone and on the PC",
        text = "Some lines hold both versions. Open the list and choose which to keep; until then, " +
            "nothing on this screen can change it.",
    ) {
        OutlinedButton(onClick = onOpenList) { Text("Open the list") }
    }
}

@Composable
private fun WarningsCard(warnings: List<String>) {
    InfoCard(title = "The list needs a look", text = warnings.joinToString("\n"))
}

@Composable
private fun InfoCard(title: String, text: String, action: (@Composable () -> Unit)? = null) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) {
                Spacer(Modifier.height(2.dp))
                action()
            }
        }
    }
}

private val LONG_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
