package com.azizjon.notes.reminders

import android.content.Context
import com.azizjon.notes.life.FileEdit
import com.azizjon.notes.life.LifeRepository
import com.azizjon.notes.life.TextMerge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** How a change from the Today screen went. */
enum class ChangeResult { DONE, NOT_FOUND, CLASH, NO_LIST }

/**
 * The reminder list of the life repo (`reminders/`; its README says how it is written): what the
 * Today screen shows, the changes made there (a commit each), the morning upkeep the reminder bot
 * used to do (checklist steps, repeating events, old events to past.md), and the reminders at
 * 08:00 and 20:00.
 */
class Reminders(private val context: Context, private val life: LifeRepository) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whether the 08:00 and 20:00 reminders are on. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            schedule()
            if (!value) ReminderNotifications.cancelAll(context)
        }

    /** The list's text as the notes index has it: null while unread, or when the repo has none. */
    val listText: Flow<String?> =
        life.notes.map { notes -> notes?.firstOrNull { it.path == UPCOMING }?.text }.distinctUntilChanged()

    /** Whether the notes index is read, so a null [listText] means the repo has no list. */
    val indexRead: Flow<Boolean> = life.notes.map { it != null }.distinctUntilChanged()

    private val _openToday = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** A reminder was tapped while the app was open: it should show Today. */
    val openToday: SharedFlow<Unit> = _openToday.asSharedFlow()

    fun requestOpenToday() {
        _openToday.tryEmit(Unit)
    }

    /** What the last upkeep could not do, such as an event naming a checklist that does not exist. */
    val warnings: List<String>
        get() = prefs.getString(KEY_WARNINGS, null).orEmpty().lines().filter { it.isNotBlank() }

    // ---- Changes from the Today screen ----

    suspend fun setMark(ref: ItemRef, label: String, mark: Char): ChangeResult {
        val verb = when (mark) {
            'x' -> "done"
            '-' -> "skipped"
            else -> "open again"
        }
        return change("Reminders: $verb: $label") { text, _ ->
            ReminderEdits.setMark(text, ref, mark)?.let { mapOf(UPCOMING to it) }
        }
    }

    suspend fun move(ref: ItemRef, label: String, to: LocalDate): ChangeResult =
        change("Reminders: moved to $to: $label") { text, _ ->
            ReminderEdits.move(text, ref, to)?.let { mapOf(UPCOMING to it) }
        }

    /** Ticks a question and keeps his answer in `inbox.md`, for the next Claude session to file. */
    suspend fun answer(ref: ItemRef, label: String, answer: String): ChangeResult =
        change("Reminders: answered: $label") { text, read ->
            val ticked = ReminderEdits.setMark(text, ref, 'x') ?: return@change null
            val entry = ReminderEdits.inboxEntry(LocalDateTime.now(), label, answer)
            mapOf(UPCOMING to ticked, INBOX to ReminderEdits.appendToInbox(read(INBOX), entry))
        }

    private suspend fun change(
        message: String,
        edit: (text: String, read: (String) -> String?) -> Map<String, String>?,
    ): ChangeResult {
        var result = ChangeResult.NOT_FOUND
        life.commitEdit { read ->
            val text = read(UPCOMING)
            when {
                text == null -> null.also { result = ChangeResult.NO_LIST }
                TextMerge.hasConflict(text) -> null.also { result = ChangeResult.CLASH }
                else -> edit(text, read)?.let { files ->
                    result = ChangeResult.DONE
                    FileEdit(message, files)
                }
            }
        }
        if (result == ChangeResult.DONE) refreshShown()
        return result
    }

    // ---- The morning upkeep ----

    /**
     * What the reminder bot did each morning, once a day: adds their checklist's steps to new
     * events, adds the coming events of repeating checklists, and moves events and finished items
     * two weeks old to `past.md`, in one commit. Skipped while the list holds a clash.
     */
    suspend fun upkeepIfDue(today: LocalDate = LocalDate.now()): List<String> {
        if (!life.isCloned() || prefs.getString(KEY_UPKEEP_DAY, null) == today.toString()) return emptyList()
        val checklists = ReminderPlan.checklists(life.readFolder(CHECKLISTS))
        var changes: List<String> = emptyList()
        var settled = true
        life.commitEdit { read ->
            val text = read(UPCOMING) ?: return@commitEdit null
            if (TextMerge.hasConflict(text)) {
                settled = false
                return@commitEdit null
            }
            val (expanded, added) = ReminderPlan.expand(text, checklists, today)
            val pruned = ReminderPlan.prune(expanded, today)
            changes = added + pruned.changes
            val real = changes.filterNot { it.startsWith("warning:") }
            if (real.isEmpty()) return@commitEdit null
            val files = buildMap {
                put(UPCOMING, pruned.text)
                if (pruned.moved.isNotEmpty()) put(PAST, ReminderPlan.appendToPast(read(PAST), pruned.moved))
            }
            FileEdit(("Reminders: " + real.joinToString("; ")).take(200), files)
        }
        if (settled) {
            prefs.edit()
                .putString(KEY_UPKEEP_DAY, today.toString())
                .putString(KEY_WARNINGS, changes.filter { it.startsWith("warning:") }.joinToString("\n") { it.removePrefix("warning: ") })
                .apply()
        }
        return changes
    }

    /** The app came to the front: the alarms are set, and today's upkeep runs if it has not. */
    fun onAppOpened() {
        if (!life.isCloned()) return
        schedule()
        if (prefs.getString(KEY_UPKEEP_DAY, null) != LocalDate.now().toString()) ReminderUpkeepWorker.enqueue(context)
    }

    // ---- The reminders ----

    fun schedule() = ReminderAlarms.schedule(context, enabled && life.isCloned())

    /** The 08:00 reminder: what is due today. False when there is nothing to tell, or it cannot show. */
    suspend fun postMorning(today: LocalDate = LocalDate.now(), alert: Boolean = true): Boolean {
        val text = life.read(UPCOMING) ?: return false
        return ReminderNotifications.morning(context, ReminderPlan.parseUpcoming(text), today, warnings, alert)
    }

    /** The 20:00 reminder: what to prepare for tomorrow's events. False when none is on tomorrow. */
    suspend fun postEvening(today: LocalDate = LocalDate.now(), alert: Boolean = true): Boolean {
        val text = life.read(UPCOMING) ?: return false
        return ReminderNotifications.evening(context, ReminderPlan.parseUpcoming(text), today, alert)
    }

    /**
     * An alarm went off, or the phone restarted or its clock changed: sends the reminder that is
     * due and has not gone out today (the morning one until noon, the evening one until 23:00, as
     * the bot did), and sets the next alarms.
     */
    suspend fun onAlarm(now: LocalDateTime = LocalDateTime.now()) {
        schedule()
        if (!enabled || !life.isCloned()) return
        val today = now.toLocalDate()
        val time = now.toLocalTime()
        if (time >= MORNING.minusMinutes(1) && time < MORNING_UNTIL && sentOn(KEY_MORNING_DAY) != today) {
            prefs.edit().putString(KEY_MORNING_DAY, today.toString()).apply()
            postMorning(today)
            ReminderUpkeepWorker.enqueue(context)
        }
        if (time >= EVENING.minusMinutes(1) && time < EVENING_UNTIL && sentOn(KEY_EVENING_DAY) != today) {
            prefs.edit().putString(KEY_EVENING_DAY, today.toString()).apply()
            postEvening(today)
        }
    }

    /** Brings the reminders still showing up to date with the list, without a sound. */
    suspend fun refreshShown() {
        val today = LocalDate.now()
        if (ReminderNotifications.isShowing(context, ReminderNotifications.MORNING_ID)) postMorning(today, alert = false)
        if (ReminderNotifications.isShowing(context, ReminderNotifications.EVENING_ID)) postEvening(today, alert = false)
    }

    private fun sentOn(key: String): LocalDate? = prefs.getString(key, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    companion object {
        const val UPCOMING = "reminders/upcoming.md"
        const val PAST = "reminders/past.md"
        const val INBOX = "reminders/inbox.md"
        const val CHECKLISTS = "reminders/checklists"

        val MORNING: LocalTime = LocalTime.of(8, 0)
        val MORNING_UNTIL: LocalTime = LocalTime.NOON
        val EVENING: LocalTime = LocalTime.of(20, 0)
        val EVENING_UNTIL: LocalTime = LocalTime.of(23, 0)

        private const val PREFS = "reminders"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_UPKEEP_DAY = "upkeep_day"
        private const val KEY_WARNINGS = "warnings"
        private const val KEY_MORNING_DAY = "morning_day"
        private const val KEY_EVENING_DAY = "evening_day"
    }
}
