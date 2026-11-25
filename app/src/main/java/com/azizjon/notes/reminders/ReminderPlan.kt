package com.azizjon.notes.reminders

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A step of a checklist: [offset] days from the event's day (day 0); negative is before it. */
data class Step(val offset: Int, val text: String, val ask: Boolean = false)

/** A kind of event and its steps, from a file in `reminders/checklists/`. */
data class Checklist(
    val name: String,
    val title: String,
    /** "none", "weekly Thu 10:30", "monthly 1st,3rd Sat 14:00", or "yearly 06-18". */
    val repeat: String,
    val steps: List<Step>,
    /** Copied into the events added for a repeating checklist. */
    val where: String = "",
) {
    val repeats: Boolean get() = repeat.trim().lowercase() !in setOf("", "none")
}

/** An event block of `upcoming.md`: its heading, `key: value` lines, and items. */
class Event(val line: Int, val date: LocalDate, val time: String?, val title: String) {
    val meta = LinkedHashMap<String, String>()
    val items = ArrayList<Item>()

    /** Index of the first line after the block. */
    var end = 0
        internal set

    val checklist: String get() = meta["checklist"].orEmpty().trim()
    val cancelled: Boolean get() = meta["status"].orEmpty().trim().lowercase() in ReminderPlan.CANCELLED
    val hasSteps: Boolean get() = "steps" in meta
}

enum class ItemKind { TASK, ASK, FOLLOW_UP }

/** One `- [ ] YYYY-MM-DD text` line: [mark] is ' ' (open), 'x' (done), or '-' (skipped). */
class Item(
    val line: Int,
    val mark: Char,
    val date: LocalDate?,
    val text: String,
    val kind: ItemKind,
    val event: Event?,
) {
    val open: Boolean get() = mark == ' '
}

class Upcoming(
    val lines: List<String>,
    val events: List<Event>,
    val items: List<Item>,
    /** Index where the Events section ends, for adding events at its end. */
    val eventsEnd: Int?,
)

/** What the morning reminder says about [day]. */
class Digest(
    val day: LocalDate,
    val eventsToday: List<Event>,
    val today: List<Item>,
    val overdue: List<Item>,
    /** Open items left out of [overdue]: older than a week, or past the first six. */
    val hidden: Int,
    val questions: List<Item>,
    val coming: List<Event>,
)

/**
 * The reminder list: reading the checklists and `reminders/upcoming.md`, adding checklist steps to
 * new and repeating events, moving old events out, and choosing what the morning and evening
 * reminders say. A port of the reminder bot's `plan.py`, a Python tool that agents on the PC run on
 * the same list: both must read the same files the same way and make the same changes (the parity
 * check in `ReminderParityTest` compares them on a real list). Pure functions over text;
 * [Reminders] does the files and git. The file formats are in the repo's `reminders/README.md`.
 */
object ReminderPlan {
    const val OVERDUE_DAYS = 7 // an open item older than this drops out of the reminders
    const val MAX_OVERDUE = 6 // at most this many "Still open" lines; the rest are counted
    const val COMING_DAYS = 7 // how far ahead "Coming up" looks
    const val QUESTION_DAYS = 14 // an unanswered question stops being asked after this
    const val MAX_QUESTIONS = 3
    const val PRUNE_DAYS = 14 // events older than this move to past.md
    const val MIN_HORIZON = 14 // repeating events are added at least this many days ahead
    val CANCELLED = setOf("cancelled", "canceled", "skipped", "not going")

    /** The start of `reminders/past.md` when it is first written (the same as `plan.py`'s). */
    const val PAST_HEADER =
        "# Past reminders\n\nEvents, and finished follow-ups and questions, moved out of upcoming.md two weeks after their day.\n"

    /** The bot's line under each message; the app's reminders leave it out. */
    const val REPLY_HINT = "Reply like \"done 1\" or \"move 2 to Friday\", or just tell me what changed."

    val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    // On Android (ICU), \s and \d match Unicode spaces and digits, as Python's do. The inline flag
    // that does this on the PC's Java, (?U), crashes ICU, so ReminderPlanTest runs on a device too.
    private val STEP_RE = Regex("""^-\s*day\s*([+-]?\d+)\s*:\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)
    private val SECTION_RE = Regex("""^##\s+(.+?)\s*$""")
    private val EVENT_RE = Regex("""^###\s+(\d{4}-\d{2}-\d{2})(?:\s+(\d{1,2}:\d{2}))?\s*[·|\-–—]+\s*(.+?)\s*$""")
    private val ITEM_RE = Regex("""^-\s*\[([ xX-])\]\s*(?:(\d{4}-\d{2}-\d{2})\s+)?(.*?)\s*$""")
    private val META_RE = Regex("""^([a-z][a-z ]{0,20}):\s*(.*?)\s*$""")
    private val REPEAT_RE = Regex("""^(weekly|monthly|yearly)\s+(.+?)(?:\s+(\d{1,2}:\d{2}))?$""", RegexOption.IGNORE_CASE)
    private val MONTH_DAY_RE = Regex("""^(\d{1,2})-(\d{1,2})$""")
    private val COMMENT_RE = Regex("""\s+#\s.*$""")
    private val WORD_SPLIT_RE = Regex("""[,\s]+""")
    private val ASK_PREFIX_RE = Regex("""^ask:\s*""", RegexOption.IGNORE_CASE)

    // ---- Lines ----

    /** Python's `str.splitlines()` for \n, \r\n, and \r: no empty last line after a final break. */
    fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val parts = text.split("\r\n", "\n", "\r")
        val endsWithBreak = text.endsWith('\n') || text.endsWith('\r')
        return if (endsWithBreak) parts.dropLast(1) else parts
    }

    /** Collapses runs of blank lines into one and drops trailing blank lines. */
    fun tidy(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        for (line in lines) {
            if (line.isBlank() && out.isNotEmpty() && out.last().isBlank()) continue
            out += line
        }
        while (out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.size - 1)
        return out
    }

    /** Index just after the last non-blank line in lines[start, end). */
    private fun lastFilled(lines: List<String>, start: Int, end: Int): Int {
        var at = end
        while (at > start && lines[at - 1].isBlank()) at--
        return at
    }

    /** Python's repr() of a string, for messages that quote one. */
    private fun repr(s: String): String =
        if ('\'' in s && '"' !in s) "\"$s\"" else "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private fun date(text: String?): LocalDate? =
        if (text.isNullOrEmpty()) null else runCatching { LocalDate.parse(text) }.getOrNull()

    // ---- Checklists ----

    /** The `key: value` lines between the opening `---` lines, and the text after them. */
    fun parseFrontmatter(text: String): Pair<Map<String, String>, String> {
        val lines = splitLines(text)
        if (lines.isEmpty() || lines[0].trim() != "---") return emptyMap<String, String>() to text
        val meta = LinkedHashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.trim() == "---") return meta to lines.drop(i + 1).joinToString("\n")
            val colon = line.indexOf(':')
            if (colon >= 0) {
                meta[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).replace(COMMENT_RE, "").trim()
            }
        }
        return emptyMap<String, String>() to text
    }

    /**
     * Steps come from `- day N: text` lines under "## Steps"; questions from the same kind of line
     * under "## What the bot should ask".
     */
    fun parseChecklist(text: String, fallbackName: String): Checklist {
        val (meta, body) = parseFrontmatter(text)
        val steps = ArrayList<Step>()
        var section: String? = null
        for (raw in splitLines(body)) {
            val line = raw.trim()
            val heading = SECTION_RE.matchEntire(line)
            if (heading != null && !line.startsWith("###")) {
                val title = heading.groupValues[1].lowercase()
                section = when {
                    title.startsWith("steps") -> "steps"
                    "ask" in title -> "ask"
                    else -> null
                }
                continue
            }
            if (section != null) {
                STEP_RE.matchEntire(line)?.let { m ->
                    steps += Step(m.groupValues[1].toInt(), m.groupValues[2], ask = section == "ask")
                }
            }
        }
        return Checklist(
            name = meta["name"].orEmpty().ifEmpty { fallbackName },
            title = meta["title"].orEmpty().ifEmpty { fallbackName },
            repeat = meta["repeat"].orEmpty().ifEmpty { "none" },
            // sortedBy is stable: steps of one day keep the file's order.
            steps = steps.sortedBy { it.offset },
            where = meta["where"].orEmpty(),
        )
    }

    /** The checklists by name, from (file name, text) pairs taken in file-name order, as the bot does. */
    fun checklists(files: Map<String, String>): LinkedHashMap<String, Checklist> {
        val out = LinkedHashMap<String, Checklist>()
        for ((fileName, text) in files.entries.sortedBy { it.key }) {
            val checklist = parseChecklist(text, fileName.removeSuffix(".md"))
            out[checklist.name] = checklist
        }
        return out
    }

    private fun parseWeekday(word: String): Int {
        val key = word.trim().lowercase().take(3)
        val index = WEEKDAYS.indexOfFirst { it.lowercase() == key }
        if (index < 0) throw IllegalArgumentException("not a weekday: ${repr(word)}")
        return index
    }

    /**
     * The days from [start] to [end] (both included) that a repeat rule lands on, with its time:
     * "weekly Thu 10:30", "weekly Mon,Thu", "monthly 1st,3rd Sat", "monthly 2nd Sun 14:00",
     * "yearly 06-18" (a 29 February happens only in leap years). Throws for a rule it cannot read.
     */
    fun occurrences(repeat: String, start: LocalDate, end: LocalDate): List<Pair<LocalDate, String?>> {
        val m = REPEAT_RE.matchEntire(repeat.trim())
            ?: throw IllegalArgumentException("cannot read repeat rule: ${repr(repeat)}")
        val kind = m.groupValues[1].lowercase()
        val rest = m.groupValues[2]
        val time = m.groups[3]?.value
        val words = rest.split(WORD_SPLIT_RE).filter { it.isNotEmpty() }
        val days = (0..ChronoUnit.DAYS.between(start, end)).map { start.plusDays(it) }
        if (kind == "yearly") {
            val md = MONTH_DAY_RE.matchEntire(rest.trim())
            val month = md?.groupValues?.get(1)?.toInt()
            val day = md?.groupValues?.get(2)?.toInt()
            if (month == null || day == null || month !in 1..12 || day !in 1..31) {
                throw IllegalArgumentException("cannot read repeat rule: ${repr(repeat)}")
            }
            return days.filter { it.monthValue == month && it.dayOfMonth == day }.map { it to time }
        }
        if (kind == "weekly") {
            val weekdays = words.map { parseWeekday(it) }.toSet()
            return days.filter { it.dayOfWeek.value - 1 in weekdays }.map { it to time }
        }
        val weekday = parseWeekday(words.last())
        val nths = words.dropLast(1).map { w ->
            w.firstOrNull()?.takeIf { it.isDigit() }?.digitToInt()
                ?: throw IllegalArgumentException("cannot read repeat rule: ${repr(repeat)}")
        }.toSet()
        return days.filter { it.dayOfWeek.value - 1 == weekday && (it.dayOfMonth - 1) / 7 + 1 in nths }.map { it to time }
    }

    // ---- The upcoming list ----

    fun parseUpcoming(text: String): Upcoming {
        val lines = splitLines(text)
        val events = ArrayList<Event>()
        val items = ArrayList<Item>()
        var section = ""
        var current: Event? = null
        var eventsEnd: Int? = null

        fun close(at: Int) {
            current?.end = at
            current = null
        }

        for ((i, raw) in lines.withIndex()) {
            val line = raw.trim()
            val heading = SECTION_RE.matchEntire(line)
            if (heading != null && !line.startsWith("###")) {
                close(i)
                if (section == "events") eventsEnd = i
                section = heading.groupValues[1].trim().lowercase()
                continue
            }
            val event = if (section == "events") EVENT_RE.matchEntire(line) else null
            val eventDate = event?.let { date(it.groupValues[1]) }
            if (event != null && eventDate != null) {
                close(i)
                current = Event(i, eventDate, event.groups[2]?.value, event.groupValues[3]).also { events += it }
                continue
            }
            val item = ITEM_RE.matchEntire(line)
            if (item != null) {
                val text = item.groupValues[3]
                val owner = current
                val kind = when {
                    section == "events" && owner != null ->
                        if (text.lowercase().startsWith("ask:")) ItemKind.ASK else ItemKind.TASK
                    section.startsWith("question") -> ItemKind.ASK
                    section.startsWith("follow") -> ItemKind.FOLLOW_UP
                    else -> continue // a checkbox in the intro or elsewhere is not a reminder
                }
                val entry = Item(i, item.groupValues[1].lowercase()[0], date(item.groups[2]?.value), text, kind, owner)
                items += entry
                owner?.items?.add(entry)
                continue
            }
            current?.let { ev ->
                META_RE.matchEntire(line)?.let { ev.meta[it.groupValues[1].trim()] = it.groupValues[2] }
            }
        }
        close(lines.size)
        if (section == "events") eventsEnd = lines.size
        return Upcoming(lines, events, items, eventsEnd)
    }

    /** The checklist's steps as dated items; "<date>" in a step becomes the event's day. */
    fun stepLines(day0: LocalDate, checklist: Checklist): List<String> = checklist.steps.map { step ->
        var text = step.text.replace("<date>", fmtDay(day0))
        if (step.ask && !text.lowercase().startsWith("ask:")) text = "Ask: $text"
        "- [ ] ${day0.plusDays(step.offset.toLong())} $text"
    }

    /**
     * Adds its checklist's steps to each event that names a checklist and has none yet, and adds
     * the coming events of repeating checklists. Returns the new text and a line per change; lines
     * starting "warning: " change nothing.
     */
    fun expand(text: String, checklists: Map<String, Checklist>, today: LocalDate): Pair<String, List<String>> {
        val lines = splitLines(text).toMutableList()
        val changes = ArrayList<String>()

        // Steps for events that name a checklist; from the bottom up, so earlier indexes stay valid.
        for (ev in parseUpcoming(text).events.sortedByDescending { it.line }) {
            if (ev.checklist.isEmpty() || ev.hasSteps || ev.cancelled) continue
            val checklist = checklists[ev.checklist]
            if (checklist == null) {
                changes += "warning: no checklist named ${repr(ev.checklist)} (${ev.date} ${ev.title})"
                continue
            }
            val at = lastFilled(lines, ev.line, ev.end)
            lines.addAll(at, listOf("steps: added $today") + stepLines(ev.date, checklist))
            val past = checklist.steps.count { ev.date.plusDays(it.offset.toLong()) < today }
            val note = if (past > 0) ", $past of them already past" else ""
            changes += "added ${checklist.steps.size} steps to ${ev.date} ${ev.title}$note"
        }

        // The coming events of repeating checklists.
        for (checklist in checklists.values) {
            if (!checklist.repeats) continue
            val earliest = checklist.steps.minOfOrNull { it.offset } ?: 0
            val horizon = maxOf(MIN_HORIZON, -earliest + 1)
            val dates = try {
                occurrences(checklist.repeat, today, today.plusDays(horizon.toLong()))
            } catch (e: IllegalArgumentException) {
                changes += "warning: ${checklist.name}: ${e.message}"
                continue
            }
            for ((day, time) in dates) {
                val up = parseUpcoming(lines.joinToString("\n"))
                if (up.events.any { it.date == day && it.checklist == checklist.name }) continue
                val block = buildList {
                    add("### $day${if (time != null) " $time" else ""} · ${checklist.title}")
                    add("checklist: ${checklist.name}")
                    if (checklist.where.isNotEmpty()) add("where: ${checklist.where}")
                    add("steps: added $today")
                    addAll(stepLines(day, checklist))
                }
                val later = up.events.filter { it.date > day }
                when {
                    later.isNotEmpty() -> lines.addAll(later.minOf { it.line }, block + "")
                    up.eventsEnd != null -> lines.addAll(lastFilled(lines, 0, up.eventsEnd), listOf("") + block + "")
                    else -> lines.addAll(listOf("", "## Events", "") + block)
                }
                changes += "added ${checklist.title} on $day"
            }
        }
        return tidy(lines).joinToString("\n") + "\n" to changes
    }

    /** What [prune] takes out: the new text, the lines for `past.md`, and a line per change. */
    class Pruned(val text: String, val moved: List<String>, val changes: List<String>)

    /** Takes out events more than [PRUNE_DAYS] old, and finished follow-ups and questions that old. */
    fun prune(text: String, today: LocalDate): Pruned {
        val up = parseUpcoming(text)
        val cutoff = today.minusDays(PRUNE_DAYS.toLong())
        val drop = HashSet<Int>()
        val moved = ArrayList<String>()
        val changes = ArrayList<String>()
        for (ev in up.events) {
            if (ev.date < cutoff) {
                val block = ev.line until ev.end
                drop.addAll(block)
                moved.addAll(block.map { up.lines[it] } + "")
                changes += "moved ${ev.date} ${ev.title} to past.md"
            }
        }
        for (item in up.items) {
            val date = item.date
            if (item.event == null && !item.open && date != null && date < cutoff) {
                drop += item.line
                moved += up.lines[item.line]
                changes += "moved a finished item of $date to past.md"
            }
        }
        val kept = up.lines.filterIndexed { i, _ -> i !in drop }
        return Pruned(tidy(kept).joinToString("\n") + "\n", tidy(moved), changes)
    }

    /** [past] (null when the file does not exist yet) with [moved] lines added at its end. */
    fun appendToPast(past: String?, moved: List<String>): String =
        (past ?: PAST_HEADER).trimEnd('\n') + "\n\n" + moved.joinToString("\n") + "\n"

    // ---- What the reminders say ----

    fun fmtDay(day: LocalDate): String = "${WEEKDAYS[day.dayOfWeek.value - 1]} ${day.dayOfMonth} ${MONTHS[day.monthValue - 1]}"

    /** A weekday for days within the last week, otherwise the date. */
    fun fmtWhen(day: LocalDate, today: LocalDate): String {
        val ago = ChronoUnit.DAYS.between(day, today)
        return if (ago in 1..6) WEEKDAYS[day.dayOfWeek.value - 1] else "${day.dayOfMonth} ${MONTHS[day.monthValue - 1]}"
    }

    /** An item's text without "Ask: ". */
    fun plainText(item: Item): String = item.text.replace(ASK_PREFIX_RE, "")

    /** The item as a reminder shows it: its event's title, then its text. */
    fun label(item: Item): String = item.event?.let { "${it.title}: ${plainText(item)}" } ?: plainText(item)

    fun eventLine(ev: Event): String {
        val where = ev.meta["where"].orEmpty().trim()
        return "${ev.time?.let { "$it " } ?: ""}${ev.title}${if (where.isNotEmpty()) " · $where" else ""}"
    }

    fun digest(up: Upcoming, today: LocalDate): Digest {
        val live = up.items.filter { it.open && it.date != null && it.event?.cancelled != true }
        val tasks = live.filter { it.kind != ItemKind.ASK }
        val late = tasks.filter { it.date!! < today }.sortedByDescending { it.date }
        val recent = late.filter { ChronoUnit.DAYS.between(it.date!!, today) <= OVERDUE_DAYS }.take(MAX_OVERDUE)
        val asks = live
            .filter { it.kind == ItemKind.ASK && it.date!! <= today && ChronoUnit.DAYS.between(it.date, today) <= QUESTION_DAYS }
            .sortedBy { it.date }
        val events = up.events.filter { !it.cancelled }
        return Digest(
            day = today,
            eventsToday = events.filter { it.date == today }.sortedBy { it.time.orEmpty() },
            today = tasks.filter { it.date == today },
            overdue = recent.sortedBy { it.date },
            hidden = late.size - recent.size,
            questions = asks.take(MAX_QUESTIONS),
            coming = events
                .filter { it.date > today && it.date <= today.plusDays(COMING_DAYS.toLong()) }
                .sortedWith(compareBy({ it.date }, { it.time.orEmpty() })),
        )
    }

    /** The bot's morning message, line for line (the parity check compares it with `plan.py`'s). */
    fun morningMessage(up: Upcoming, today: LocalDate, warnings: List<String> = emptyList(), hint: String? = REPLY_HINT): String {
        val d = digest(up, today)
        val out = arrayListOf("Good morning! ${fmtDay(today)}")
        var n = 0
        fun add(text: String) {
            n++
            out += "$n. $text"
        }
        if (d.eventsToday.isNotEmpty() || d.today.isNotEmpty()) {
            out += listOf("", "Today")
            out += d.eventsToday.map { "📅 ${eventLine(it)}" }
            d.today.forEach { add(label(it)) }
        } else {
            out += listOf("", "Nothing due today.")
        }
        if (d.overdue.isNotEmpty()) {
            out += listOf("", "Still open")
            d.overdue.forEach { add("${label(it)} (due ${fmtWhen(it.date!!, today)})") }
            if (d.hidden > 0) out += "+${d.hidden} older, still open in the list"
        }
        if (d.questions.isNotEmpty()) {
            out += listOf("", "Questions")
            d.questions.forEach { add(label(it)) }
        }
        if (d.coming.isNotEmpty()) {
            out += listOf("", "Coming up")
            out += d.coming.map { "${fmtDay(it.date)}${it.time?.let { t -> " $t" } ?: ""} ${it.title}" }
        }
        warnings.forEach { out += listOf("", "⚠️ $it") }
        if (n > 0 && hint != null) out += listOf("", hint)
        return out.joinToString("\n")
    }

    /** What to prepare for tomorrow's events, or null when nothing is on tomorrow. */
    fun eveningMessage(up: Upcoming, today: LocalDate, hint: String? = REPLY_HINT): String? {
        val tomorrow = today.plusDays(1)
        val events = up.events.filter { it.date == tomorrow && !it.cancelled }.sortedBy { it.time.orEmpty() }
        if (events.isEmpty()) return null
        val out = ArrayList<String>()
        var n = 0
        for (ev in events) {
            if (out.isNotEmpty()) out += ""
            out += "Tomorrow: ${ev.title}, ${fmtDay(ev.date)}${ev.time?.let { " $it" } ?: ""}"
            ev.meta["where"]?.takeIf { it.isNotEmpty() }?.let { out += "Where: $it" }
            val live = ev.items.filter { it.open && it.date != null }
            val now = live.filter { it.kind != ItemKind.ASK && (it.date == today || it.date == tomorrow) }
            val lateAll = live
                .filter { it.kind != ItemKind.ASK && it.date!! < today && ChronoUnit.DAYS.between(it.date, today) <= OVERDUE_DAYS }
                .sortedByDescending { it.date }
            val late = lateAll.take(MAX_OVERDUE) // the most recent ones, as in the morning
            val asks = live.filter { it.kind == ItemKind.ASK && it.date!! <= tomorrow }.take(MAX_QUESTIONS)
            for ((heading, group, lateNote) in listOf(
                Triple("Tonight and tomorrow", now, false),
                Triple("Still open for it", late, true),
                Triple("Questions", asks, false),
            )) {
                if (group.isEmpty()) continue
                out += listOf("", heading)
                for (item in group.sortedBy { it.date }) {
                    n++
                    val due = if (lateNote) " (due ${fmtWhen(item.date!!, today)})" else ""
                    out += "$n. ${plainText(item)}$due"
                }
                if (lateNote && lateAll.size > late.size) out += "+${lateAll.size - late.size} older, still open in the list"
            }
        }
        if (n > 0 && hint != null) out += listOf("", hint)
        return out.joinToString("\n")
    }
}
