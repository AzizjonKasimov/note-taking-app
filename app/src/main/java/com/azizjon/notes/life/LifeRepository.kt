package com.azizjon.notes.life

import android.content.Context
import android.util.Log
import com.azizjon.notes.data.NotesDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import javax.net.ssl.SSLException

/** A Markdown note of the repo, as the list shows it. */
data class NoteEntry(
    val path: String,
    val title: String,
    /** The top-level folder ("" for a file at the root). */
    val folder: String,
    val preview: String,
    val changedAt: Long,
    /** True while the note holds both versions of a clash between conflict markers. */
    val conflicted: Boolean,
    val text: String,
)

sealed interface Connection {
    data object Off : Connection
    data class Cloning(val step: String, val percent: Int?) : Connection
    data object Ready : Connection
}

data class SyncStatus(
    val running: Boolean = false,
    val lastSyncAt: Long = 0L,
    val error: String? = null,
)

/** New text for some files of the repo (null deletes one), and the message of the commit that saves them. */
class FileEdit(val message: String, val files: Map<String, String?>)

/** What a background sync should tell WorkManager. */
enum class SyncResult { DONE, RETRY, STOP }

/**
 * The app's one way into the life repo on the phone: every read and write of a note, and every
 * sync, goes through here, one at a time. Before the first of them it finishes any sync that
 * Android killed halfway ([LifeSync.recover]), so nothing writes over a half-moved working tree.
 */
class LifeRepository(private val context: Context, private val scope: CoroutineScope) {
    val settings = LifeSettings(context)
    private val tokens = TokenStore(context)
    val workDir: File = File(context.noBackupFilesDir, "life")
    private val files = NoteFiles(workDir)
    private val trash = LocalTrash(File(context.filesDir, "life-trash"))
    private val changeTimes = ChangeTimes(File(context.noBackupFilesDir, "life-times.txt"))
    /** Held for local work on the clone (git's working tree, index, and branch, and note files). */
    private val treeLock = ReentrantLock(true)
    /** One sync, connect, or disconnect at a time. A sync takes [treeLock] for its local steps only. */
    private val syncMutex = Mutex()
    @Volatile private var recovered = false
    @Volatile private var dirtySinceSync = false
    @Volatile private var times: Map<String, Long> = emptyMap()
    private val localEdits = ConcurrentHashMap<String, Long>()
    private val syncsRunning = AtomicInteger()

    private val _connection = MutableStateFlow<Connection>(if (isCloned()) Connection.Ready else Connection.Off)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    /** Every note, newest first; null until the list is first read. */
    private val _notes = MutableStateFlow<List<NoteEntry>?>(null)
    val notes: StateFlow<List<NoteEntry>?> = _notes.asStateFlow()

    private val _sync = MutableStateFlow(SyncStatus(lastSyncAt = settings.lastSyncAt, error = settings.lastSyncError))
    val sync: StateFlow<SyncStatus> = _sync.asStateFlow()

    /** Paths a sync rewrote on the phone, so an open note can take in the new text. */
    private val _changed = MutableSharedFlow<Set<String>>(extraBufferCapacity = 16)
    val changed: SharedFlow<Set<String>> = _changed.asSharedFlow()

    private val _trash = MutableStateFlow<List<TrashEntry>>(emptyList())
    val trashed: StateFlow<List<TrashEntry>> = _trash.asStateFlow()

    /** How many of the app's old notes were moved into the inbox; shown once. */
    private val _movedAppNotes = MutableStateFlow(0)
    val movedAppNotes: StateFlow<Int> = _movedAppNotes.asStateFlow()

    fun isCloned(): Boolean = File(workDir, ".git").isDirectory

    val repoName: String? get() = settings.repoName

    private var started = false

    /** Reads the note list and moves the old app notes, once per process. */
    fun start() {
        if (!isCloned() || started) return
        started = true
        scope.launch {
            _trash.value = withContext(Dispatchers.IO) { trash.list() }
            refreshIndex()
            moveAppNotesIfNeeded()
        }
    }

    /** The app came to the front: take in GitHub's changes, unless a sync ran moments ago. */
    fun onForeground() {
        if (!isCloned()) return
        start()
        if (System.currentTimeMillis() - _sync.value.lastSyncAt < 30_000L) return
        scope.launch { sync() }
    }

    // ---- Connecting ----

    /**
     * Checks [token] with GitHub, finds the repo it opens (or uses [repo]: "owner/name" or a URL),
     * and clones it. Throws [IOException] with a message for the screen.
     */
    suspend fun connect(token: String, repo: String?) {
        val clean = token.trim()
        if (clean.isEmpty()) throw IOException("Paste the token first.")
        withContext(Dispatchers.IO) {
            try {
                _connection.value = Connection.Cloning("Checking the token", null)
                val target = if (repo.isNullOrBlank()) {
                    val repos = GitHubAccess.repos(clean)
                    GitHubAccess.pick(repos)?.cloneUrl ?: throw IOException(
                        if (repos.none { it.private }) {
                            "This token can't open a private repo. Give it access to your life repo."
                        } else {
                            "This token opens ${repos.count { it.private }} private repos. " +
                                "Type the one to use, such as owner/life."
                        },
                    )
                } else {
                    GitHubAccess.cloneUrl(repo)
                }
                if (target.startsWith("https://github.com/")) {
                    val account = GitHubAccess.account(clean)
                    settings.login = account.login
                    settings.authorName = account.name
                    settings.authorEmail = account.noReplyEmail
                }
                tokens.save(clean)
                syncMutex.withLock {
                    treeLock.withLock {
                        _connection.value = Connection.Cloning("Downloading", null)
                        LifeSync.clone(target, workDir, credentials(clean), CloneProgress())
                        recovered = true
                    }
                }
                settings.remoteUrl = target
                settings.repoName = GitHubAccess.repoName(target)
                settings.lastSyncAt = System.currentTimeMillis()
                settings.lastSyncError = null
                _sync.value = SyncStatus(lastSyncAt = settings.lastSyncAt)
            } catch (e: Exception) {
                Log.w(TAG, "Connecting failed", e)
                tokens.clear()
                File(workDir.parentFile, workDir.name + ".partial").deleteRecursively()
                _connection.value = Connection.Off
                throw IOException(friendly(e).first, e)
            }
        }
        SyncWorker.schedulePeriodic(context)
        // The connect screen closes once this says Ready, cancelling its own coroutines, so the
        // rest runs in the app's scope.
        _connection.value = Connection.Ready
        scope.launch {
            refreshIndex()
            moveAppNotesIfNeeded()
            sync()
        }
    }

    /** True while the phone holds changes that GitHub does not have yet. */
    suspend fun hasUnsyncedChanges(): Boolean = locked {
        Git.open(workDir).use { git ->
            val repo = git.repository
            !git.status().call().isClean || repo.resolve(LifeSync.LOCAL_REF) != repo.resolve(LifeSync.REMOTE_REF)
        }
    }

    /** Forgets the token and deletes the phone's copy of the repo. */
    suspend fun disconnect() {
        syncMutex.withLock {
            locked {
                workDir.deleteRecursively()
                recovered = false
            }
        }
        tokens.clear()
        settings.clear()
        changeTimes.clear()
        times = emptyMap()
        localEdits.clear()
        SyncWorker.cancelAll(context)
        _notes.value = null
        _sync.value = SyncStatus()
        _connection.value = Connection.Off
    }

    /** Replaces the token, keeping the phone's copy. */
    suspend fun replaceToken(token: String) {
        val clean = token.trim()
        if (clean.isEmpty()) throw IOException("Paste the token first.")
        withContext(Dispatchers.IO) {
            if (settings.remoteUrl?.startsWith("https://github.com/") == true) {
                try {
                    GitHubAccess.account(clean).let {
                        settings.login = it.login
                        settings.authorName = it.name
                        settings.authorEmail = it.noReplyEmail
                    }
                } catch (e: Exception) {
                    throw IOException(friendly(e).first, e)
                }
            }
            tokens.save(clean)
        }
        scope.launch { sync() }
    }

    // ---- Notes ----

    suspend fun read(path: String): String? = locked { files.read(path) }

    /** Saves an open editor's text; see [NoteFiles.writeFromEditor]. */
    suspend fun writeFromEditor(path: String, text: String, base: String?): EditorWrite {
        val result = locked { files.writeFromEditor(path, text, base) }
        noteChanged(path)
        return result
    }

    suspend fun newNotePath(folder: String): String = locked { files.newNotePath(folder) }

    /** Renames a new note after its title, when that name is free. Returns the path it ends up at. */
    suspend fun nameAfterTitle(path: String, title: String): String {
        val target = locked {
            files.pathForTitle(path, title)?.takeIf { files.exists(path) }?.also { files.move(path, it) }
        } ?: return path
        localEdits.remove(path)
        noteChanged(target)
        _notes.update { list -> list?.filterNot { it.path == path } }
        return target
    }

    /** Deletes a note; a copy stays in this phone's Trash. */
    suspend fun delete(path: String) {
        locked {
            val text = files.read(path) ?: return@locked
            trash.keep(path, text)
            files.delete(path)
        }
        localEdits.remove(path)
        dirtySinceSync = true
        _notes.update { list -> list?.filterNot { it.path == path } }
        _trash.value = withContext(Dispatchers.IO) { trash.list() }
        SyncWorker.syncSoon(context, delaySeconds = 5)
    }

    /** Puts a deleted note back, beside a newer note of the same name if there is one. */
    suspend fun restore(entry: TrashEntry): String? {
        val path = locked {
            val text = trash.text(entry) ?: return@locked null
            val target = if (files.exists(entry.path)) {
                files.newNotePath(entry.path.substringBeforeLast('/', ""))
            } else {
                entry.path
            }
            files.write(target, text)
            trash.remove(entry)
            target
        }
        _trash.value = withContext(Dispatchers.IO) { trash.list() }
        if (path != null) noteChanged(path)
        return path
    }

    /** Deletes a new note left blank, with no copy in Trash. */
    suspend fun discardBlank(path: String) {
        val deleted = locked { files.read(path)?.takeIf { it.isBlank() }?.let { files.delete(path) } ?: false }
        if (deleted) {
            localEdits.remove(path)
            _notes.update { list -> list?.filterNot { it.path == path } }
        }
    }

    /** Asks for a sync in the background in [delaySeconds] (when online). */
    fun syncSoon(delaySeconds: Long) {
        if (isCloned()) SyncWorker.syncSoon(context, delaySeconds)
    }

    suspend fun deleteForever(entry: TrashEntry) {
        withContext(Dispatchers.IO) { trash.remove(entry) }
        _trash.value = withContext(Dispatchers.IO) { trash.list() }
    }

    suspend fun emptyTrash() {
        withContext(Dispatchers.IO) { trash.clear() }
        _trash.value = emptyList()
    }

    /** Makes a top-level folder with a README, as the repo's topics have. Returns its name. */
    suspend fun createFolder(name: String): String? {
        val slug = NoteFiles.slugify(name)
        if (slug.isEmpty()) return null
        locked {
            if (!files.exists("$slug/README.md")) files.write("$slug/README.md", "# ${name.trim()}\n")
        }
        noteChanged("$slug/README.md")
        return slug
    }

    // ---- Changes with a commit of their own ----

    /**
     * Changes files and commits just them, so each change made on the Today screen, and each
     * morning's upkeep of the reminder list, is a commit of its own on GitHub. [edit] runs alone on
     * the clone and reads the files as they are now; it returns their new text and the commit's
     * message, or null to change nothing. Returns the paths it committed.
     */
    suspend fun commitEdit(edit: (read: (String) -> String?) -> FileEdit?): Set<String> {
        val written = locked {
            val change = edit { files.read(it) } ?: return@locked emptySet()
            RepoCommit.apply(files, change.message, change.files, identity())
        }
        written.forEach { noteChanged(it) }
        if (written.isNotEmpty()) SyncWorker.syncSoon(context, delaySeconds = 5)
        return written
    }

    /**
     * Runs [block] alone on the clone's files, for reads that must not see a sync halfway, such as
     * the assistant's listing and searching. [block] must not write.
     */
    suspend fun <T> readTree(block: (NoteFiles) -> T): T = locked { block(files) }

    /** The Markdown files directly inside [folder], by file name (empty when there is none). */
    suspend fun readFolder(folder: String): Map<String, String> = locked {
        files.file(folder).listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty()
            .associate { it.name to it.readText(Charsets.UTF_8) }
    }

    private suspend fun noteChanged(path: String) {
        localEdits[path] = System.currentTimeMillis()
        dirtySinceSync = true
        val entry = withContext(Dispatchers.IO) { entryFor(path) }
        _notes.update { list ->
            val rest = list.orEmpty().filterNot { it.path == path }
            (if (entry == null) rest else rest + entry).sortedByDescending { it.changedAt }
        }
    }

    // ---- Sync ----

    /** Commits, pulls, and pushes. Safe to call from anywhere; syncs run one at a time. */
    suspend fun sync(): SyncResult {
        if (!isCloned()) return SyncResult.STOP
        val token = withContext(Dispatchers.IO) { tokens.read() }
            ?: return fail("Enter your GitHub token again in Settings.", SyncResult.STOP)
        syncsRunning.incrementAndGet()
        _sync.update { it.copy(running = true) }
        try {
            val outcome = syncMutex.withLock {
                withContext(Dispatchers.IO) {
                    dirtySinceSync = false
                    LifeSync(workDir, credentials(token), ::identity, treeLock = treeLock).sync()
                        .also { recovered = true }
                }
            }
            val now = System.currentTimeMillis()
            settings.lastSyncAt = now
            settings.lastSyncError = null
            outcome.phoneChanges.forEach { localEdits.remove(it) }
            refreshIndex()
            if (outcome.changedPaths.isNotEmpty()) _changed.emit(outcome.changedPaths)
            _sync.update { it.copy(lastSyncAt = now, error = null) }
            if (dirtySinceSync) SyncWorker.syncSoon(context, delaySeconds = 5)
            return SyncResult.DONE
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed", e)
            val (message, result) = friendly(e)
            return fail(message, result)
        } finally {
            if (syncsRunning.decrementAndGet() == 0) _sync.update { it.copy(running = false) }
        }
    }

    private fun fail(message: String, result: SyncResult): SyncResult {
        settings.lastSyncError = message
        _sync.update { it.copy(error = message) }
        return result
    }

    // ---- The list ----

    /** Reads every note again (titles, previews) with the last-changed times from history. */
    suspend fun refreshIndex() {
        if (!isCloned()) return
        val list = withContext(Dispatchers.IO) {
            times = treeLock.withLock {
                runCatching { Git.open(workDir).use { changeTimes.update(it.repository) } }.getOrDefault(times)
            }
            files.listMarkdown().mapNotNull { entryFor(it) }
        }
        _notes.value = list.sortedByDescending { it.changedAt }
    }

    private fun entryFor(path: String): NoteEntry? {
        val text = runCatching { files.read(path) }.getOrNull() ?: return null
        val changedAt = maxOf(localEdits[path] ?: 0L, times[path] ?: 0L)
            .takeIf { it > 0L } ?: files.file(path).lastModified()
        return NoteEntry(
            path = path,
            title = noteTitle(path, text),
            folder = if ('/' in path) path.substringBefore('/') else "",
            preview = notePreview(text),
            changedAt = changedAt,
            conflicted = TextMerge.hasConflict(text),
            text = text,
        )
    }

    // ---- The app's old notes ----

    private suspend fun moveAppNotesIfNeeded() {
        if (settings.appNotesMoved) return
        if (!context.getDatabasePath(NotesDatabase.NAME).exists()) {
            settings.appNotesMoved = true
            return
        }
        val db = NotesDatabase.get(context)
        val chosen = AppNotesMove.toMove(db.noteDao().getAll())
        if (chosen.isNotEmpty()) {
            val names = db.notebookDao().getAll().associate { it.id to it.name }
            val paths = locked { AppNotesMove.write(files, chosen, names) }
            paths.forEach { noteChanged(it) }
        }
        settings.appNotesMoved = true
        _movedAppNotes.value = chosen.size
    }

    fun consumeMovedAppNotes() {
        _movedAppNotes.value = 0
    }

    // ---- Helpers ----

    /** Runs [block] alone on the clone, after finishing any sync a kill left halfway. */
    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) {
        treeLock.withLock {
            if (!recovered && isCloned()) {
                LifeSync(workDir, credentials = null, identity = ::identity, treeLock = treeLock).recover()
                recovered = true
            }
            block()
        }
    }

    private fun identity() = PersonIdent(settings.authorName, settings.authorEmail)

    private fun credentials(token: String) =
        UsernamePasswordCredentialsProvider(settings.login ?: "x-access-token", token)

    private inner class CloneProgress : ProgressMonitor {
        private var task = ""
        private var total = 0
        private var done = 0
        private var shown = -1

        override fun start(totalTasks: Int) = Unit
        override fun beginTask(title: String?, totalWork: Int) {
            task = title.orEmpty()
            total = totalWork
            done = 0
            shown = -1
            publish()
        }

        override fun update(completed: Int) {
            done += completed
            publish()
        }

        override fun endTask() = Unit
        override fun isCancelled() = false

        private fun publish() {
            val percent = if (total > 0) (done * 100L / total).toInt().coerceIn(0, 100) else null
            if (percent == shown && percent != null) return
            shown = percent ?: -1
            _connection.value = Connection.Cloning(task, percent)
        }
    }

    companion object {
        private const val TAG = "Life"

        /** A message for the screen, and whether a background sync should try again. */
        fun friendly(e: Throwable): Pair<String, SyncResult> {
            val chain = generateSequence(e) { it.cause }.toList()
            val text = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
            return when {
                chain.any { it is GitHubAccess.TokenRejected } ||
                    "not authorized" in text || "authentication is required" in text || "401" in text ->
                    "GitHub did not accept the token. Make a new one and enter it in Settings." to SyncResult.STOP
                "403" in text || "permission" in text || "denied" in text ->
                    "The token can't write to this repo. It needs Contents: Read and write." to SyncResult.STOP
                chain.any {
                    it is UnknownHostException || it is ConnectException || it is SocketTimeoutException ||
                        it is SSLException
                } || (e is TransportException && chain.any { it is IOException }) ->
                    "No connection to GitHub. Your changes are saved on the phone and will sync later." to
                        SyncResult.RETRY
                e is IOException && e.message != null -> e.message!! to SyncResult.RETRY
                else -> (e.message ?: e::class.java.simpleName) to SyncResult.RETRY
            }
        }
    }
}
