package com.azizjon.notes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.azizjon.notes.NotesApplication
import com.azizjon.notes.life.TextMerge
import com.azizjon.notes.reminders.ChangeResult
import com.azizjon.notes.reminders.Digest
import com.azizjon.notes.reminders.Item
import com.azizjon.notes.reminders.ItemKind
import com.azizjon.notes.reminders.ItemRef
import com.azizjon.notes.reminders.ReminderEdits
import com.azizjon.notes.reminders.ReminderPlan
import com.azizjon.notes.reminders.Reminders
import com.azizjon.notes.reminders.Upcoming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

sealed interface TodayState {
    data object Loading : TodayState
    data object NoList : TodayState

    /** [allLate] holds every late task once he asked to see the older ones too. */
    class Ready(
        val up: Upcoming,
        val digest: Digest,
        val day: LocalDate,
        val clash: Boolean,
        val allLate: List<Item>?,
    ) : TodayState
}

/** A line for the snackbar, with a way to undo the change when there is one. */
class TodayMessage(val text: String, val undo: (() -> Unit)? = null)

/** The Today screen: what the morning reminder lists, kept live, and the changes made there. */
class TodayViewModel(private val reminders: Reminders) : ViewModel() {
    private val day = MutableStateFlow(LocalDate.now())
    private val showAllLate = MutableStateFlow(false)
    private val _messages = MutableSharedFlow<TodayMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<TodayMessage> = _messages.asSharedFlow()

    val state: StateFlow<TodayState> =
        combine(reminders.listText, reminders.indexRead, day, showAllLate) { text, read, today, allLate ->
            when {
                text == null && !read -> TodayState.Loading
                text == null -> TodayState.NoList
                else -> {
                    val up = ReminderPlan.parseUpcoming(text)
                    TodayState.Ready(
                        up = up,
                        digest = ReminderPlan.digest(up, today),
                        day = today,
                        clash = TextMerge.hasConflict(text),
                        allLate = if (allLate) lateTasks(up, today) else null,
                    )
                }
            }
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState.Loading)

    init {
        // Today moves on at midnight while the screen stays open.
        viewModelScope.launch {
            while (true) {
                val now = LocalDateTime.now()
                delay(Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).toMillis() + 1_000)
                day.value = LocalDate.now()
            }
        }
    }

    val remindersOn: Boolean get() = reminders.enabled
    val warnings: List<String> get() = reminders.warnings

    /** The screen came back: the day may have changed. */
    fun onResume() {
        day.value = LocalDate.now()
    }

    fun toggleAllLate() = showAllLate.update { !it }

    fun done(up: Upcoming, item: Item) = mark(up, item, 'x', "Done")

    fun skip(up: Upcoming, item: Item) = mark(up, item, '-', "Skipped")

    fun move(up: Upcoming, item: Item, to: LocalDate) {
        val ref = ReminderEdits.ref(up, item)
        val label = ReminderPlan.label(item)
        val from = item.date
        viewModelScope.launch {
            when (val result = reminders.move(ref, label, to)) {
                ChangeResult.DONE -> {
                    val after = ReminderEdits.move(ref.raw, ItemRef(0, ref.raw), to)?.let { ItemRef(ref.line, it) }
                    val undo = if (after != null && from != null) ({ moveBack(after, label, from) }) else null
                    _messages.emit(TodayMessage("Moved to ${ReminderPlan.fmtDay(to)}: ${ReminderPlan.plainText(item)}", undo))
                }
                else -> _messages.emit(problem(result))
            }
        }
    }

    /** Ticks a question and keeps the answer in the list's inbox, for the next Claude session. */
    fun answer(up: Upcoming, item: Item, answer: String) {
        val ref = ReminderEdits.ref(up, item)
        viewModelScope.launch {
            when (val result = reminders.answer(ref, ReminderPlan.label(item), answer)) {
                ChangeResult.DONE -> _messages.emit(TodayMessage("Answer saved. The next Claude session puts it where it belongs."))
                else -> _messages.emit(problem(result))
            }
        }
    }

    private fun mark(up: Upcoming, item: Item, mark: Char, verb: String) {
        val ref = ReminderEdits.ref(up, item)
        val label = ReminderPlan.label(item)
        val before = item.mark
        viewModelScope.launch {
            when (val result = reminders.setMark(ref, label, mark)) {
                ChangeResult.DONE -> {
                    val after = ReminderEdits.setMark(ref.raw, ItemRef(0, ref.raw), mark)?.let { ItemRef(ref.line, it) }
                    _messages.emit(TodayMessage("$verb: ${ReminderPlan.plainText(item)}", after?.let { { markBack(it, label, before) } }))
                }
                else -> _messages.emit(problem(result))
            }
        }
    }

    private fun markBack(ref: ItemRef, label: String, mark: Char) {
        viewModelScope.launch {
            reminders.setMark(ref, label, mark).takeIf { it != ChangeResult.DONE }?.let { _messages.emit(problem(it)) }
        }
    }

    private fun moveBack(ref: ItemRef, label: String, to: LocalDate) {
        viewModelScope.launch {
            reminders.move(ref, label, to).takeIf { it != ChangeResult.DONE }?.let { _messages.emit(problem(it)) }
        }
    }

    private fun problem(result: ChangeResult) = TodayMessage(
        when (result) {
            ChangeResult.NOT_FOUND -> "That item changed in the meantime, so nothing changed. Look at it again."
            ChangeResult.CLASH -> "The list holds both the phone's and the PC's version of some lines. Settle that first."
            ChangeResult.NO_LIST -> "This repo has no reminder list."
            ChangeResult.DONE -> ""
        },
    )

    companion object {
        /** Every late task, oldest first, for "Show older". */
        fun lateTasks(up: Upcoming, today: LocalDate): List<Item> = up.items
            .filter { it.open && it.kind != ItemKind.ASK && it.event?.cancelled != true && (it.date?.isBefore(today) == true) }
            .sortedBy { it.date }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { TodayViewModel((this[APPLICATION_KEY] as NotesApplication).reminders) }
        }
    }
}
