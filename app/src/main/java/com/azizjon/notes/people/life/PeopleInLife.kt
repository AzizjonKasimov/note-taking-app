package com.azizjon.notes.people.life

import android.content.Context
import com.azizjon.notes.BuildConfig
import com.azizjon.notes.life.FileEdit
import com.azizjon.notes.life.GitHubAccess
import com.azizjon.notes.life.LifeRepository
import com.azizjon.notes.people.PeopleStorage
import com.azizjon.notes.people.data.NetworkRepository
import com.azizjon.notes.people.data.PeoplePayload
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

open class PeopleSaveException(message: String) : Exception(message)

/** A save held back because it would remove more people's files than an ordinary change does. */
class PeopleRemovalException(val removing: Int) :
    PeopleSaveException("Saving would remove $removing people's files from your notes repo at once, so nothing was saved.")

/** A draft a session on the PC left in `people/drafts/`, waiting for the user to send it. */
data class InboxDraft(val id: String, val text: String, val queuedAt: Long)

data class SaveStatus(
    val lastSavedAt: Long = 0,
    val lastAttemptAt: Long = 0,
    val lastError: String = "",
    val saveNeeded: Boolean = false,
)

/** When People last saved to the notes repo, and whether a change is waiting to be saved. */
class PeopleSaveState(context: Context) {
    private val prefs = context.getSharedPreferences(PeopleStorage.SAVE_PREFS, Context.MODE_PRIVATE)

    val status: SaveStatus
        get() = SaveStatus(
            lastSavedAt = prefs.getLong(KEY_LAST_SAVED_AT, 0L),
            lastAttemptAt = prefs.getLong(KEY_LAST_ATTEMPT_AT, 0L),
            lastError = prefs.getString(KEY_LAST_ERROR, "").orEmpty(),
            saveNeeded = prefs.getBoolean(KEY_SAVE_NEEDED, false),
        )

    /** A test build writes the people folder only once switched on, so it never overwrites the real one. */
    var testBuildOwnsFolder: Boolean
        get() = prefs.getBoolean(KEY_TEST_BUILD_OWNS, false)
        set(value) = prefs.edit().putBoolean(KEY_TEST_BUILD_OWNS, value).apply()

    /** How many people came over from Network App on the phone; null until they did. */
    val movedFromNetworkApp: Int?
        get() = if (prefs.contains(KEY_MOVED)) prefs.getInt(KEY_MOVED, 0) else null

    fun markMovedFromNetworkApp(people: Int) {
        prefs.edit().putInt(KEY_MOVED, people).putBoolean(KEY_SAVE_NEEDED, true).commit()
    }

    /** Written before a change, so a process killed halfway still leaves the save due. */
    fun markSaveNeeded() {
        prefs.edit().putBoolean(KEY_SAVE_NEEDED, true).apply()
    }

    fun markAttemptStarted() {
        prefs.edit().putLong(KEY_LAST_ATTEMPT_AT, System.currentTimeMillis()).apply()
    }

    fun markSaved() {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong(KEY_LAST_SAVED_AT, now)
            .putLong(KEY_LAST_ATTEMPT_AT, now)
            .putString(KEY_LAST_ERROR, "")
            .putBoolean(KEY_SAVE_NEEDED, false)
            .apply()
    }

    fun markFailed(message: String) {
        prefs.edit()
            .putLong(KEY_LAST_ATTEMPT_AT, System.currentTimeMillis())
            .putString(KEY_LAST_ERROR, message.trim().take(300))
            .putBoolean(KEY_SAVE_NEEDED, true)
            .apply()
    }

    private companion object {
        const val KEY_LAST_SAVED_AT = "last_saved_at"
        const val KEY_LAST_ATTEMPT_AT = "last_attempt_at"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_SAVE_NEEDED = "save_needed"
        const val KEY_TEST_BUILD_OWNS = "test_build_owns_folder"
        const val KEY_MOVED = "moved_from_network_app"
    }
}

/**
 * People in the notes repo: writes [PeopleFiles] from the phone's records through the repo's one
 * way in ([LifeRepository.commitEdit], whose commit the next sync pushes), reads them back into an
 * empty People, and keeps the drafts the PC leaves in `people/drafts/`.
 */
class PeopleInLife(
    private val life: LifeRepository,
    private val repository: NetworkRepository,
    private val state: PeopleSaveState,
) {
    /** The released app owns the people folder; a test build only when switched on in People's settings. */
    val owns: Boolean get() = !BuildConfig.DEBUG || state.testBuildOwnsFolder

    /**
     * Writes every person whose file changed, and removes the files of people who are gone. A save
     * that would remove more than [MAX_REMOVED_AT_ONCE] people's files is refused unless
     * [allowRemovals], which the user gives in People's settings. Returns how many people there are.
     */
    suspend fun save(allowRemovals: Boolean = false): Int {
        if (!owns) throw PeopleSaveException("This test build does not save people to your notes repo.")
        if (!life.isCloned()) throw PeopleSaveException("Connect your notes repo first.")
        val payload = PeoplePayload(repository.snapshot(), repository.allFeedback())
        // Before the move, People is empty, and an empty save would remove everyone's file.
        if (payload.snapshot.people.isEmpty()) throw PeopleSaveException("There is nobody to save yet.")
        val wanted = PeopleFiles.files(payload)
        val stale = personFiles() - wanted.keys
        // An ordinary change removes one person (a delete or a merge); many at once means something is wrong.
        val gone = gone(stale, payload.snapshot.people.map { it.id }.toSet())
        if (gone > MAX_REMOVED_AT_ONCE && !allowRemovals) throw PeopleRemovalException(gone)
        requirePrivateRepo()
        val names = payload.snapshot.people.associate { PeopleFiles.pathOf(it) to it.name }
        life.commitEdit { read ->
            val changes = (wanted + stale.associateWith { null }).filter { (path, text) -> read(path) != text }
            if (changes.isEmpty()) null else FileEdit(commitMessage(changes, names), changes)
        }
        return payload.snapshot.people.size
    }

    /** How many people's files the folder holds. */
    suspend fun folderPeople(): Int = personFiles().size

    /** The folder's people and reports, checked, for a People that is still empty. */
    suspend fun load(): PeoplePayload {
        val texts = life.readTree { files ->
            files.file(PeopleFiles.FOLDER).listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
                .associate { "${PeopleFiles.FOLDER}/${it.name}" to it.readText(Charsets.UTF_8) }
        }
        if (texts.keys.none(PeopleFiles::isPersonFile)) throw PeopleSaveException("Your notes repo has no people yet.")
        return PeopleFiles.read(texts)
    }

    /** The drafts waiting in `people/drafts/`, oldest first; none for a test build that does not own the folder. */
    suspend fun drafts(): List<InboxDraft> {
        if (!owns || !life.isCloned()) return emptyList()
        return life.readTree { files ->
            files.file(PeopleFiles.DRAFTS).listFiles { f -> f.isFile && f.name.endsWith(DRAFT_SUFFIX) }.orEmpty()
                .map { f ->
                    val id = f.name.removeSuffix(DRAFT_SUFFIX)
                    InboxDraft(id, f.readText(Charsets.UTF_8).trim().take(MAX_DRAFT_CHARACTERS), queuedAt(id) ?: f.lastModified())
                }
                .filter { it.text.isNotEmpty() }
                .sortedBy { it.queuedAt }
        }
    }

    /** Removes a draft the user sent, moved into the message box, or discarded. */
    suspend fun takeDraft(id: String) {
        if (!owns || !DRAFT_ID.matches(id)) return
        val path = "${PeopleFiles.DRAFTS}/$id$DRAFT_SUFFIX"
        life.commitEdit { read -> if (read(path) == null) null else FileEdit("People: took a draft from the PC", mapOf(path to null)) }
    }

    /** When GitHub last said, in this run of the app, that the notes repo is private. */
    @Volatile private var privateCheckedAt = 0L

    /**
     * People's files go only into a private repo (Network App's rule for its backups, kept when
     * they became files in the notes repo). GitHub is asked at most once an hour, so saving does not
     * wait on the network after each change; a repo that is not on GitHub (the emulator's test
     * server, which only test builds reach) is not asked.
     */
    private suspend fun requirePrivateRepo() {
        val now = System.currentTimeMillis()
        if (now - privateCheckedAt < PRIVATE_CHECK_EVERY_MS) return
        val private = try {
            life.isPrivateOnGitHub()
        } catch (e: GitHubAccess.TokenRejected) {
            throw PeopleSaveException("Not saved: GitHub did not accept your token. Enter it again in Settings.")
        } catch (e: IOException) {
            throw PeopleSaveException("Not saved yet: GitHub could not be asked whether your notes repo is private. People saves once the phone is online.")
        }
        if (private == false) {
            throw PeopleSaveException("Not saved: your notes repo is public on GitHub, and People's files go only into a private repo.")
        }
        privateCheckedAt = now
    }

    private suspend fun personFiles(): Set<String> = life.readTree { files ->
        files.file(PeopleFiles.FOLDER).listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .map { "${PeopleFiles.FOLDER}/${it.name}" }
            .filter(PeopleFiles::isPersonFile)
            .toSet()
    }

    companion object {
        const val MAX_REMOVED_AT_ONCE = 5
        private const val PRIVATE_CHECK_EVERY_MS = 60 * 60 * 1000L

        /** The people assistant's limit for one message. */
        const val MAX_DRAFT_CHARACTERS = 4_000

        /** `scripts/queue-draft.ps1` names a draft by when it was queued and a random part: 20261128T201530Z-1a2b3c4d.txt. */
        const val DRAFT_SUFFIX = ".txt"
        private val DRAFT_ID = Regex("^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$")
        private val DRAFT_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

        /**
         * How many of the [stale] files belong to people no longer among [ids]. A renamed person's
         * old file is stale too, but they are not gone, so a new naming rule never blocks a save.
         */
        internal fun gone(stale: Set<String>, ids: Set<Long>): Int = stale.count { PeopleFiles.idOf(it) !in ids }

        internal fun queuedAt(id: String): Long? = runCatching {
            LocalDateTime.parse(id.substringBefore('-'), DRAFT_TIME).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()

        /** "People: Dana Whitfield, Priya Raman, and 3 more", naming whose files changed. */
        internal fun commitMessage(changes: Map<String, String?>, names: Map<String, String>): String {
            val who = changes.keys.sorted().map { path ->
                when {
                    path == PeopleFiles.REPORTS -> "reports"
                    changes[path] == null -> "removed ${path.substringAfterLast('/').removeSuffix(".json")}"
                    else -> names[path] ?: path.substringAfterLast('/').removeSuffix(".json")
                }
            }
            val shown = who.take(3).joinToString(", ")
            return "People: " + if (who.size > 3) "$shown, and ${who.size - 3} more" else shown
        }
    }
}
