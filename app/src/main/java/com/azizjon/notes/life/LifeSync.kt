package com.azizjon.notes.life

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.errors.CheckoutConflictException
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheCheckout
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.io.IOException
import java.util.TreeMap
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The points of a sync where the clash harness can step in. */
enum class SyncStep { COMMITTED, FETCHED, MERGED, BEFORE_CHECKOUT, CHECKED_OUT, MOVED, BEFORE_PUSH, PUSHED }

/** A move of the phone's main branch from one commit to another. */
data class BranchMove(val from: ObjectId, val to: ObjectId)

/**
 * Test seam. The clash harness throws from here to act as if Android killed the app at that
 * point, or pushes from its "PC" so the phone's push is turned down. The app passes nothing.
 */
fun interface SyncHooks {
    fun at(step: SyncStep, move: BranchMove?)
}

/** What one sync did. */
data class SyncOutcome(
    /** Files the phone changed that went into this sync's commit. */
    val phoneChanges: List<String>,
    /** True when changes from GitHub came into the phone's copy. */
    val pulled: Boolean,
    /** True when GitHub took the phone's changes. */
    val pushed: Boolean,
    /** Files that both sides changed on the same lines: both versions are in them, between markers. */
    val conflicts: List<String>,
    /** Files kept twice, as "path -> copy", where no line merge was possible (binary files). */
    val keptBoth: List<String>,
    /** Files this sync changed on the phone, so open screens can reload them. */
    val changedPaths: Set<String>,
)

class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Keeps a clone of the life repo in step with GitHub: commits the phone's edits, takes in GitHub's,
 * and pushes. It never force-pushes and never drops an edit:
 *
 * - When both sides changed, it merges them in memory (a three-way merge of every file) and makes
 *   one new commit on top of GitHub's, so history stays a straight line that `git pull --ff-only`
 *   on the PC can always follow.
 * - Where both changed the same lines of a note, both versions stay in the note between conflict
 *   markers ([TextMerge]). A note deleted on one side and edited on the other is kept, edited.
 * - The working tree is rewritten before the branch moves, and a small journal in `.git` records
 *   the move, so if Android kills the app halfway, the next sync finishes it.
 *
 * Everything here blocks: call it off the main thread, one call at a time.
 */
class LifeSync(
    val workTree: File,
    private val credentials: CredentialsProvider?,
    private val identity: () -> PersonIdent,
    private val hooks: SyncHooks = SyncHooks { _, _ -> },
    private val timeoutSeconds: Int = 60,
    /**
     * Held while the sync touches the working tree, the index, or the branch, and released for
     * the network, so the app can open and save notes while a slow fetch or push runs.
     */
    private val treeLock: Lock = ReentrantLock(),
) {

    /** One sync. Only one may run at a time per working tree (the caller sees to that). */
    fun sync(): SyncOutcome = Git.open(workTree).use { git -> syncWith(git) }

    /** Finishes a move that a killed sync left halfway and clears stale lock files. */
    fun recover() {
        Git.open(workTree).use { git -> treeLock.withLock { recover(git) } }
    }

    /** Commits the phone's edits without the network, so they are safe in git while offline. */
    fun commitLocal(): List<String> = Git.open(workTree).use { git ->
        treeLock.withLock {
            recover(git)
            commitLocal(git)
        }
    }

    private enum class Next { DONE, PUSH }

    private fun syncWith(git: Git): SyncOutcome {
        val repo = git.repository
        val phoneChanges = sortedSetOf<String>()
        treeLock.withLock {
            recover(git)
            if (repo.repositoryState != RepositoryState.SAFE) {
                throw SyncException("The phone's copy of the repo is in the middle of a ${repo.repositoryState}.")
            }
            phoneChanges += commitLocal(git)
        }
        hooks.at(SyncStep.COMMITTED, null)

        val conflicts = LinkedHashSet<String>()
        val keptBoth = LinkedHashSet<String>()
        val changed = LinkedHashSet<String>()
        var pulled = false
        fun outcome(pushed: Boolean) =
            SyncOutcome(phoneChanges.toList(), pulled, pushed, conflicts.toList(), keptBoth.toList(), changed)

        repeat(MAX_ATTEMPTS) {
            fetch(git)
            hooks.at(SyncStep.FETCHED, null)
            val next = treeLock.withLock {
                // Notes saved while the fetch ran go into this sync too.
                phoneChanges += commitLocal(git)
                RevWalk(repo).use { walk ->
                    val head = walk.parseCommit(
                        repo.resolve(LOCAL_REF) ?: throw SyncException("No $BRANCH branch on the phone."),
                    )
                    val remote = walk.parseCommit(
                        repo.resolve(REMOTE_REF) ?: throw SyncException("GitHub has no $BRANCH branch."),
                    )
                    when {
                        head == remote -> Next.DONE
                        walk.isMergedInto(remote, head) -> Next.PUSH // only the phone moved on
                        walk.isMergedInto(head, remote) -> {
                            changed += move(git, head, remote, "sync: take GitHub's changes")
                            pulled = true
                            Next.DONE
                        }
                        else -> {
                            val base = mergeBase(walk, head, remote)
                            val merged = mergeTrees(repo, base, head, remote)
                            conflicts += merged.conflicts
                            keptBoth += merged.keptBoth
                            pulled = true
                            if (merged.tree == remote.tree.id) {
                                // Every change the phone had is on GitHub already.
                                changed += move(git, head, remote, "sync: take GitHub's changes")
                                Next.DONE
                            } else {
                                val commit = walk.parseCommit(
                                    writeCommit(repo, merged.tree, remote, mergedMessage(repo, remote, merged, phoneMessages(repo, head, base))),
                                )
                                hooks.at(SyncStep.MERGED, BranchMove(head, commit))
                                changed += move(git, head, commit, "sync: put the phone's changes on GitHub's")
                                Next.PUSH
                            }
                        }
                    }
                }
            }
            if (next == Next.DONE) return outcome(pushed = false)
            hooks.at(SyncStep.BEFORE_PUSH, null)
            if (push(git)) {
                hooks.at(SyncStep.PUSHED, null)
                return outcome(pushed = true)
            }
            // GitHub moved on while this sync ran: fetch and merge again.
        }
        throw SyncException("GitHub kept changing while the phone synced. Try again in a minute.")
    }

    // ---- The phone's own edits ----

    private fun commitLocal(git: Git): List<String> {
        if (git.status().call().isClean) return emptyList()
        git.add().addFilepattern(".").call()
        git.add().addFilepattern(".").setUpdate(true).call()
        val staged = git.status().call()
        val changes = staged.added.map { 'A' to it } +
            staged.changed.map { 'M' to it } +
            staged.removed.map { 'D' to it }
        if (changes.isEmpty()) return emptyList()
        val who = identity()
        git.commit()
            .setAuthor(who)
            .setCommitter(who)
            .setNoVerify(true)
            .setMessage(describe(changes.sortedBy { it.second }))
            .call()
        return changes.map { it.second }.sorted()
    }

    // ---- Network ----

    private fun fetch(git: Git) {
        git.fetch()
            .setRemote(REMOTE)
            .setRefSpecs(RefSpec("+$LOCAL_REF:$REMOTE_REF"))
            .setCredentialsProvider(credentials)
            .setTimeout(timeoutSeconds)
            .call()
    }

    /** True when GitHub took the phone's main; false when it had moved on and turned it down. */
    private fun push(git: Git): Boolean {
        val results = git.push()
            .setRemote(REMOTE)
            .setRefSpecs(RefSpec("$LOCAL_REF:$LOCAL_REF"))
            .setCredentialsProvider(credentials)
            .setTimeout(timeoutSeconds)
            .call()
        val update = results.firstNotNullOfOrNull { it.getRemoteUpdate(LOCAL_REF) }
            ?: throw SyncException("GitHub did not answer the push.")
        return when (update.status) {
            RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE -> true
            RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD,
            RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED,
            -> false
            else -> throw SyncException(
                "GitHub turned down the phone's changes (${update.status}" +
                    (update.message?.let { ": $it" } ?: "") + ").",
            )
        }
    }

    // ---- Merging ----

    private class Side(val mode: Int, val id: ObjectId) {
        override fun equals(other: Any?) = other is Side && other.mode == mode && other.id == id
        override fun hashCode() = id.hashCode() * 31 + mode
    }

    private class TreeMerge(val tree: ObjectId, val conflicts: List<String>, val keptBoth: List<String>)

    private fun mergeBase(walk: RevWalk, a: RevCommit, b: RevCommit): RevCommit? {
        walk.reset()
        walk.revFilter = RevFilter.MERGE_BASE
        walk.markStart(walk.parseCommit(a))
        walk.markStart(walk.parseCommit(b))
        val base = walk.next()
        walk.reset()
        walk.revFilter = RevFilter.ALL
        return base?.let { walk.parseCommit(it) }
    }

    /** Three-way merge of whole trees, in memory: the working tree is not touched. */
    private fun mergeTrees(repo: Repository, base: RevCommit?, phone: RevCommit, other: RevCommit): TreeMerge {
        val result = TreeMap<String, Side>()
        val conflicts = ArrayList<String>()
        val keptBoth = ArrayList<String>()
        repo.newObjectInserter().use { inserter ->
            repo.newObjectReader().use { reader ->
                TreeWalk(repo, reader).use { tw ->
                    tw.isRecursive = true
                    if (base != null) tw.addTree(base.tree) else tw.addTree(EmptyTreeIterator())
                    tw.addTree(phone.tree)
                    tw.addTree(other.tree)
                    while (tw.next()) {
                        val path = tw.pathString
                        val b = side(tw, 0)
                        val p = side(tw, 1)
                        val o = side(tw, 2)
                        val pick = when {
                            p == o -> p
                            p == b -> o
                            o == b -> p
                            p == null -> o // deleted on the phone, edited on the PC: keep the edit
                            o == null -> p // deleted on the PC, edited on the phone: keep the edit
                            else -> {
                                val merged = mergeFile(reader, inserter, b, p, o)
                                if (merged != null) {
                                    if (merged.second) conflicts += path
                                    merged.first
                                } else {
                                    // Not text: keep the PC's file in place and the phone's beside it.
                                    val copy = copyPath(path, result.keys)
                                    result[copy] = p
                                    keptBoth += "$path -> $copy"
                                    o
                                }
                            }
                        }
                        if (pick != null) result[path] = pick
                    }
                }
            }
            resolveFileFolderClashes(result, keptBoth)
            val dirCache = DirCache.newInCore()
            val builder = dirCache.builder()
            for ((path, side) in result) {
                builder.add(DirCacheEntry(path).apply {
                    setFileMode(FileMode.fromBits(side.mode))
                    setObjectId(side.id)
                })
            }
            builder.finish()
            val tree = dirCache.writeTree(inserter)
            inserter.flush()
            return TreeMerge(tree, conflicts, keptBoth)
        }
    }

    private fun side(tw: TreeWalk, index: Int): Side? {
        val mode = tw.getRawMode(index)
        return if (mode == 0) null else Side(mode, tw.getObjectId(index))
    }

    /** Merges one file changed on both sides; null when it is not text. The flag says "conflicted". */
    private fun mergeFile(
        reader: ObjectReader,
        inserter: ObjectInserter,
        base: Side?,
        phone: Side,
        other: Side,
    ): Pair<Side, Boolean>? {
        if (!isFile(phone.mode) || !isFile(other.mode) || (base != null && !isFile(base.mode))) return null
        val baseBytes = base?.let { reader.open(it.id).bytes }
        val phoneBytes = reader.open(phone.id).bytes
        val otherBytes = reader.open(other.id).bytes
        if (TextMerge.isBinary(phoneBytes) || TextMerge.isBinary(otherBytes) ||
            (baseBytes != null && TextMerge.isBinary(baseBytes))
        ) {
            return null
        }
        val merged = TextMerge.merge(baseBytes, phoneBytes, otherBytes)
        val id = inserter.insert(Constants.OBJ_BLOB, merged.bytes)
        // A mode change on one side wins (such as a script made executable).
        val mode = if (base != null && phone.mode == base.mode) other.mode else phone.mode
        return Side(mode, id) to merged.conflicted
    }

    private fun isFile(mode: Int) = mode == FileMode.REGULAR_FILE.bits || mode == FileMode.EXECUTABLE_FILE.bits

    /** A file on one side where the other has a folder of the same name: the file moves aside. */
    private fun resolveFileFolderClashes(result: TreeMap<String, Side>, keptBoth: MutableList<String>) {
        val clashing = result.keys.filter { path ->
            val next = result.ceilingKey("$path/")
            next != null && next.startsWith("$path/")
        }
        for (path in clashing) {
            val side = result.remove(path) ?: continue
            val copy = copyPath(path, result.keys)
            result[copy] = side
            keptBoth += "$path -> $copy"
        }
    }

    private fun copyPath(path: String, taken: Set<String>): String {
        val dir = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var n = 1
        while (true) {
            val label = if (n == 1) " (phone)" else " (phone $n)"
            val candidate = (if (dir.isEmpty()) "" else "$dir/") + stem + label + ext
            if (candidate !in taken) return candidate
            n++
        }
    }

    private fun writeCommit(repo: Repository, tree: ObjectId, parent: RevCommit, message: String): ObjectId {
        repo.newObjectInserter().use { inserter ->
            val who = identity()
            val id = inserter.insert(CommitBuilder().apply {
                setTreeId(tree)
                setParentId(parent)
                setAuthor(who)
                setCommitter(who)
                setMessage(message)
            })
            inserter.flush()
            return id
        }
    }

    /** The messages of the phone's own commits since [base], oldest first (at most [MAX_LISTED]). */
    private fun phoneMessages(repo: Repository, head: RevCommit, base: RevCommit?): List<String> =
        RevWalk(repo).use { rw ->
            rw.markStart(rw.parseCommit(head))
            if (base != null) rw.markUninteresting(rw.parseCommit(base))
            rw.take(MAX_LISTED).map { it.fullMessage.trimEnd() }.reversed()
        }

    /**
     * The message of the commit that puts the phone's changes on GitHub's: the phone's own commit
     * message when it made one (such as a reminder ticked on the Today screen), else what changed,
     * with the subject of each of the phone's commits.
     */
    private fun mergedMessage(repo: Repository, remote: RevCommit, merged: TreeMerge, phone: List<String>): String {
        val changes = ArrayList<Pair<Char, String>>()
        repo.newObjectReader().use { reader ->
            TreeWalk(repo, reader).use { tw ->
                tw.isRecursive = true
                tw.addTree(remote.tree)
                tw.addTree(merged.tree)
                while (tw.next()) {
                    val before = tw.getRawMode(0)
                    val after = tw.getRawMode(1)
                    if (before == after && tw.idEqual(0, 1)) continue
                    changes += when {
                        before == 0 -> 'A'
                        after == 0 -> 'D'
                        else -> 'M'
                    } to tw.pathString
                }
            }
        }
        val sb = StringBuilder(phone.singleOrNull() ?: describe(changes))
        if (phone.size > 1) sb.append("\n\n").append(phone.joinToString("\n") { "- " + it.lineSequence().first() })
        if (merged.conflicts.isNotEmpty()) {
            sb.append("\n\nThe phone and GitHub changed the same lines of ")
                .append(merged.conflicts.joinToString(", "))
                .append(". Both versions are kept there, between <<<<<<< phone and >>>>>>> PC.")
        }
        if (merged.keptBoth.isNotEmpty()) {
            sb.append("\n\nKept twice, the phone's copy beside GitHub's: ")
                .append(merged.keptBoth.joinToString(", "))
                .append('.')
        }
        return sb.toString()
    }

    // ---- Moving the branch, with a journal for a kill in the middle ----

    private fun move(git: Git, from: RevCommit, to: RevCommit, why: String): Set<String> {
        val repo = git.repository
        writeJournal(repo, BranchMove(from, to))
        hooks.at(SyncStep.BEFORE_CHECKOUT, BranchMove(from, to))
        val checkout = DirCacheCheckout(repo, from.tree, repo.lockDirCache(), to.tree)
        checkout.setFailOnConflict(true)
        try {
            checkout.checkout()
        } catch (e: CheckoutConflictException) {
            // Checked before any file is written: nothing changed, so there is nothing to finish.
            clearJournal(repo)
            throw SyncException("Files on the phone are in the way of GitHub's changes: ${e.conflictingFiles?.joinToString()}", e)
        }
        val changed = LinkedHashSet<String>(checkout.updated.keys).apply { addAll(checkout.removed) }
        hooks.at(SyncStep.CHECKED_OUT, BranchMove(from, to))
        setMain(repo, from, to, why)
        clearJournal(repo)
        hooks.at(SyncStep.MOVED, BranchMove(from, to))
        return changed
    }

    private fun setMain(repo: Repository, from: ObjectId, to: ObjectId, why: String) {
        val update = repo.updateRef(LOCAL_REF)
        update.setExpectedOldObjectId(from)
        update.setNewObjectId(to)
        update.isForceUpdate = true
        update.setRefLogMessage(why, false)
        when (val result = update.update()) {
            RefUpdate.Result.FAST_FORWARD,
            RefUpdate.Result.FORCED,
            RefUpdate.Result.NEW,
            RefUpdate.Result.NO_CHANGE,
            -> Unit
            else -> throw SyncException("Could not move the phone's $BRANCH branch ($result).")
        }
    }

    private fun recover(git: Git) {
        val repo = git.repository
        removeStaleLocks(repo.directory)
        val journal = readJournal(repo) ?: return
        val head = repo.resolve(LOCAL_REF)
        if (head == journal.from && repo.objectDatabase.has(journal.to)) {
            // Killed between rewriting files and moving the branch. Nothing has written to the
            // working tree since (every write waits for this), so finish the move.
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(journal.to.name).call()
        }
        clearJournal(repo)
    }

    private fun journalFile(repo: Repository) = File(repo.directory, JOURNAL)

    private fun writeJournal(repo: Repository, move: BranchMove) {
        val file = journalFile(repo)
        val tmp = File(repo.directory, "$JOURNAL.tmp")
        tmp.writeText("${move.from.name}\n${move.to.name}\n")
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Cannot write ${file.path}")
        }
    }

    private fun readJournal(repo: Repository): BranchMove? {
        val lines = journalFile(repo).takeIf { it.isFile }?.readLines() ?: return null
        return runCatching { BranchMove(ObjectId.fromString(lines[0]), ObjectId.fromString(lines[1])) }.getOrNull()
    }

    private fun clearJournal(repo: Repository) {
        journalFile(repo).delete()
    }

    companion object {
        const val BRANCH = "main"
        const val REMOTE = "origin"
        const val LOCAL_REF = Constants.R_HEADS + BRANCH
        const val REMOTE_REF = Constants.R_REMOTES + REMOTE + "/" + BRANCH
        private const val JOURNAL = "notes-app-move"
        private const val MAX_ATTEMPTS = 5
        private const val MAX_LISTED = 50

        /**
         * Clones [url] into [dir]. The clone is made beside it and renamed into place at the end,
         * so a clone cut short never looks like a finished one.
         */
        fun clone(
            url: String,
            dir: File,
            credentials: CredentialsProvider?,
            monitor: ProgressMonitor? = null,
            timeoutSeconds: Int = 120,
        ) {
            val partial = File(dir.parentFile, dir.name + ".partial")
            partial.deleteRecursively()
            Git.cloneRepository()
                .setURI(url)
                .setDirectory(partial)
                .setBranch(LOCAL_REF)
                .setCloneAllBranches(false)
                .setBranchesToClone(listOf(LOCAL_REF))
                .setCredentialsProvider(credentials)
                .setProgressMonitor(monitor ?: NullProgressMonitor.INSTANCE)
                .setTimeout(timeoutSeconds)
                .call()
                .use { git -> configure(git.repository) }
            if (dir.exists()) throw IOException("${dir.path} already exists")
            if (!partial.renameTo(dir)) throw IOException("Cannot move the clone into ${dir.path}")
        }

        /**
         * Settings the phone needs: no automatic gc (JGit 5.13 cannot run it on Android, and its
         * background check after a fetch would hold `gc.log.lock`), no line-ending changes.
         */
        fun configure(repo: Repository) {
            repo.config.apply {
                setInt("gc", null, "auto", 0)
                setInt("gc", null, "autopacklimit", 0)
                setBoolean("gc", null, "autoDetach", false)
                setBoolean("core", null, "autocrlf", false)
            }.save()
        }

        /**
         * Deletes `*.lock` files that a killed git operation left in `.git`, and half-written notes
         * from [NoteFiles.write]. Safe because only one sync or write runs at a time.
         */
        fun removeStaleLocks(gitDir: File) {
            gitDir.listFiles()?.filter { it.isFile && it.name.endsWith(".lock") }?.forEach { it.delete() }
            File(gitDir, "notes-tmp").listFiles()?.forEach { it.delete() }
            for (sub in listOf("refs", "logs")) {
                File(gitDir, sub).walkTopDown().filter { it.isFile && it.name.endsWith(".lock") }.forEach { it.delete() }
            }
        }

        /** "Edit a.md on the phone", or "Edit 3 notes on the phone" with a line per file. */
        fun describe(changes: List<Pair<Char, String>>): String {
            fun verb(kind: Char) = when (kind) {
                'A' -> "Add"
                'D' -> "Delete"
                else -> "Edit"
            }
            if (changes.size == 1) {
                val (kind, path) = changes.single()
                return "${verb(kind)} $path on the phone"
            }
            val kinds = changes.map { it.first }.toSet()
            val verb = if (kinds.size == 1) verb(kinds.single()) else "Edit"
            return "$verb ${changes.size} notes on the phone\n\n" +
                changes.joinToString("\n") { (kind, path) -> "- ${verb(kind)} $path" }
        }
    }
}
