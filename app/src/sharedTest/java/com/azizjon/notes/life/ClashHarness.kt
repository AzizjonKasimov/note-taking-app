package com.azizjon.notes.life

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.File
import kotlin.random.Random

/**
 * Makes clashing edits on a "phone" (the app's own [LifeSync] and [NoteFiles]) and a "PC" (plain
 * git commits and pushes, as agents make them) around a shared "GitHub" repo, and checks that
 * nothing is lost.
 *
 * Every line in the notes is a unique token. A token may only disappear when the side that removed
 * it had it in its own copy. Along the way the harness kills syncs at every step (once in the
 * middle of rewriting files), leaves stale lock files, pushes from the PC while the phone syncs,
 * and saves from an "open editor" whose text is older than the file. At the end both sides must
 * hold the same files, GitHub's main must only ever have moved forward, and every surviving token
 * must still be there.
 */
class ClashHarness(private val root: File, private val seed: Long) {

    class Report {
        var phoneEdits = 0
        var pcEdits = 0
        var editorSaves = 0
        var commitEdits = 0
        var syncs = 0
        var crashes = 0
        var partialCheckouts = 0
        var pushesDuringSync = 0
        var editsDuringSync = 0
        var staleLocks = 0
        var conflictFiles = 0
        var keptBoth = 0
        var tokensAlive = 0
        var tokensRemoved = 0
        override fun toString() =
            "syncs=$syncs phoneEdits=$phoneEdits pcEdits=$pcEdits editorSaves=$editorSaves commitEdits=$commitEdits " +
                "crashes=$crashes partialCheckouts=$partialCheckouts pushesDuringSync=$pushesDuringSync " +
                "editsDuringSync=$editsDuringSync " +
                "staleLocks=$staleLocks conflictFiles=$conflictFiles keptBoth=$keptBoth " +
                "tokensAlive=$tokensAlive tokensRemoved=$tokensRemoved"
    }

    private class SimulatedKill : RuntimeException("simulated kill")

    private val rnd = Random(seed)
    private val remoteDir = File(root, "github.git")
    private val pcDir = File(root, "pc")
    private val phoneDir = File(root, "phone")
    private val remoteUrl get() = remoteDir.absolutePath
    private val phoneFiles = NoteFiles(phoneDir)
    private val report = Report()

    private var nextToken = 0
    private val created = HashSet<String>()
    private val removed = HashSet<String>()
    private var lastRemoteHead: ObjectId? = null

    /** An editor that loaded a note and has not saved yet: its path, the text it loaded, its edits. */
    private var openEditor: Triple<String, String, String>? = null

    private val pcIdent get() = PersonIdent("PC", "pc@example.invalid")
    private val phoneIdent get() = PersonIdent("Phone", "phone@example.invalid")

    fun run(rounds: Int): Report {
        setUp()
        repeat(rounds) { round ->
            when (rnd.nextInt(100)) {
                in 0 until 30 -> phoneEdit()
                in 30 until 55 -> pcEdit()
                in 55 until 65 -> editorStep()
                in 65 until 72 -> phoneCommitEdit()
                else -> phoneSync(round)
            }
        }
        // Finish any open editor, then converge: the phone syncs, the PC pulls.
        openEditor?.let { saveEditor(it) }
        openEditor = null
        phoneSync(round = -1, allowTrouble = false)
        pcPull()
        checkConverged()
        return report
    }

    // ---- Setup ----

    private fun setUp() {
        root.deleteRecursively()
        root.mkdirs()
        Git.init().setBare(true).setDirectory(remoteDir).setInitialBranch("main").call().use { git ->
            // JGit garbage-collects a repo that received a push on a background thread once it has
            // many packs (receive.autogc), rewriting packed-refs while this harness reads it; on
            // Windows that read then fails at random (seed 26 of a 40-seed run, 2026-11-25). GitHub
            // is no JGit repo, and the phone's clone has gc off, so the stand-ins have it off too.
            git.repository.config.apply {
                setBoolean("receive", null, "autogc", false)
                setInt("gc", null, "auto", 0)
            }.save()
        }
        Git.init().setDirectory(pcDir).setInitialBranch("main").call().use { git ->
            for (name in listOf("a", "b", "c", "d")) {
                writePc("notes/$name.md", List(6) { newToken("I") }.joinToString("\n", postfix = "\n"))
            }
            writePc("README.md", List(4) { newToken("I") }.joinToString("\n", postfix = "\n"))
            File(pcDir, "pic.bin").writeBytes(byteArrayOf(0, 1, 2, 3, 0, 9))
            git.add().addFilepattern(".").call()
            git.commit().setAuthor(pcIdent).setCommitter(pcIdent).setMessage("Start").call()
            git.remoteAdd().setName("origin").setUri(org.eclipse.jgit.transport.URIish(remoteUrl)).call()
            git.push().setRemote("origin").add("main").call()
            git.repository.config.apply {
                setString("branch", "main", "remote", "origin")
                setString("branch", "main", "merge", "refs/heads/main")
                setInt("gc", null, "auto", 0)
            }.save()
        }
        LifeSync.clone(remoteUrl, phoneDir, credentials = null)
        lastRemoteHead = remoteHead()
    }

    // ---- The phone ----

    /** Files that differ from the phone's last commit. */
    private fun dirtyPaths(git: Git): Set<String> {
        val status = git.status().call()
        return status.modified + status.untracked + status.missing + status.added + status.changed + status.removed
    }

    /** A phone edit, returning the paths it touched (for edits made while a sync runs). */
    private fun phoneEditPaths(): Set<String> {
        val before = phoneFiles.listMarkdown().associateWith { phoneFiles.read(it) }
        phoneEdit()
        val after = phoneFiles.listMarkdown().associateWith { phoneFiles.read(it) }
        return (before.keys + after.keys).filter { before[it] != after[it] }.toSet() + "pic.bin"
    }

    private fun phoneEdit() {
        report.phoneEdits++
        if (rnd.nextInt(20) == 0) {
            // Rare on a real phone, but it is how a file that can't be merged line by line is tested.
            File(phoneDir, "pic.bin").writeBytes(byteArrayOf(0, 5, rnd.nextInt(100).toByte()))
            return
        }
        editTree(phoneFiles.listMarkdown(), "P",
            read = { phoneFiles.read(it) },
            write = { path, text -> phoneFiles.write(path, text) },
            delete = { phoneFiles.delete(it) },
        )
    }

    /** Opens a note in an "editor", or edits and saves the one open, possibly after syncs in between. */
    private fun editorStep() {
        val open = openEditor
        if (open == null) {
            val paths = phoneFiles.listMarkdown()
            if (paths.isEmpty()) return
            val path = paths[rnd.nextInt(paths.size)]
            val text = phoneFiles.read(path) ?: return
            openEditor = Triple(path, text, text)
        } else if (rnd.nextBoolean()) {
            // Type into the editor without saving yet.
            val (path, base, current) = open
            openEditor = Triple(path, base, editText(current, "P"))
        } else {
            saveEditor(open)
            openEditor = null
        }
    }

    /**
     * One note changed and committed on its own, with its own message, as a reminder ticked on the
     * Today screen is (`LifeRepository.commitEdit`): the phone's branch moves between syncs, or
     * while one waits on the network.
     */
    private fun phoneCommitEdit() {
        val paths = phoneFiles.listMarkdown()
        if (paths.isEmpty()) return
        val path = paths[rnd.nextInt(paths.size)]
        val text = phoneFiles.read(path) ?: return
        val edited = editText(text, "P")
        if (edited == text) return
        report.commitEdits++
        phoneFiles.write(path, edited)
        Git.open(phoneDir).use { git ->
            git.add().addFilepattern(path).call()
            // Back to its committed text: nothing to commit (LifeRepository.commitEdit skips it too).
            val status = git.status().addPath(path).call()
            if ((status.added + status.changed + status.removed).isEmpty()) return
            git.commit().setAuthor(phoneIdent).setCommitter(phoneIdent).setNoVerify(true)
                .setOnly(path).setMessage("Reminders: done: $path").call()
        }
    }

    private fun saveEditor(open: Triple<String, String, String>) {
        val (path, base, current) = open
        val edited = editText(current, "P")
        report.editorSaves++
        phoneFiles.writeFromEditor(path, edited, base)
    }

    private fun phoneSync(round: Int, allowTrouble: Boolean = true) {
        report.syncs++
        if (allowTrouble && rnd.nextInt(10) == 0) {
            // A kill in the middle of an index write leaves index.lock behind.
            File(phoneDir, ".git/index.lock").writeText("")
            report.staleLocks++
        }
        val killAt = if (allowTrouble && rnd.nextInt(4) == 0) SyncStep.entries[rnd.nextInt(SyncStep.entries.size)] else null
        val pushFromPc = allowTrouble && rnd.nextInt(4) == 0
        // The app saves notes while a sync waits on the network (it holds no lock then).
        val editDuringNetwork = if (allowTrouble && rnd.nextInt(4) == 0) {
            if (rnd.nextBoolean()) SyncStep.FETCHED else SyncStep.BEFORE_PUSH
        } else {
            null
        }
        // ...and commits a reminder change then, as a tick on the Today screen does.
        val commitDuringNetwork = if (allowTrouble && rnd.nextInt(6) == 0) {
            if (rnd.nextBoolean()) SyncStep.FETCHED else SyncStep.BEFORE_PUSH
        } else {
            null
        }
        var pushed = false
        val editedDuringSync = HashSet<String>()
        val hooks = SyncHooks { step, move ->
            if (step == editDuringNetwork) {
                report.editsDuringSync++
                editedDuringSync += phoneEditPaths()
            }
            if (step == commitDuringNetwork) phoneCommitEdit()
            if (step == SyncStep.BEFORE_PUSH && pushFromPc && !pushed) {
                pushed = true
                report.pushesDuringSync++
                pcEdit()
            }
            if (step == killAt) {
                if (step == SyncStep.BEFORE_CHECKOUT && move != null) {
                    halfCheckout(move)
                    report.partialCheckouts++
                }
                throw SimulatedKill()
            }
        }
        val sync = LifeSync(phoneDir, credentials = null, identity = { phoneIdent }, hooks = hooks)
        try {
            val outcome = sync.sync()
            report.conflictFiles += outcome.conflicts.size
            report.keptBoth += outcome.keptBoth.size
            Git.open(phoneDir).use { git ->
                val left = dirtyPaths(git)
                check((left - editedDuringSync).isEmpty()) { "round $round: phone not clean after a sync: $left" }
            }
            val locks = File(phoneDir, ".git").walkTopDown().filter { it.name.endsWith(".lock") }.toList()
            check(locks.isEmpty()) { "round $round: lock files left after a sync: $locks" }
        } catch (e: SimulatedKill) {
            report.crashes++
            // The app starts again: the first thing it does, before any write, is recover.
            LifeSync(phoneDir, credentials = null, identity = { phoneIdent }).recover()
            Git.open(phoneDir).use { git ->
                val left = dirtyPaths(git)
                check((left - editedDuringSync).isEmpty()) {
                    "round $round: phone not clean after recovering from a kill at $killAt: $left"
                }
            }
        }
        checkRemoteMovedForward("phone sync, round $round")
    }

    /** Writes part of a branch move's files, as a sync killed while rewriting files would. */
    private fun halfCheckout(move: BranchMove) {
        Git.open(phoneDir).use { git ->
            val repo = git.repository
            RevWalk(repo).use { walk ->
                val from = walk.parseCommit(move.from)
                val to = walk.parseCommit(move.to)
                TreeWalk(repo).use { tw ->
                    tw.isRecursive = true
                    tw.addTree(from.tree)
                    tw.addTree(to.tree)
                    while (tw.next()) {
                        if (tw.idEqual(0, 1) && tw.getRawMode(0) == tw.getRawMode(1)) continue
                        if (rnd.nextBoolean()) continue
                        val file = File(phoneDir, tw.pathString)
                        if (tw.getRawMode(1) == 0) {
                            file.delete()
                        } else {
                            file.parentFile?.mkdirs()
                            file.writeBytes(repo.open(tw.getObjectId(1)).bytes)
                        }
                    }
                }
            }
        }
    }

    // ---- The PC ----

    private fun pcEdit() {
        report.pcEdits++
        pcPull()
        Git.open(pcDir).use { git ->
            val paths = NoteFiles(pcDir).listMarkdown()
            editTree(paths, "C",
                read = { File(pcDir, it).takeIf { f -> f.isFile }?.readText() },
                write = { path, text -> writePc(path, text) },
                delete = { File(pcDir, it).delete() },
            )
            if (rnd.nextInt(8) == 0) {
                File(pcDir, "pic.bin").writeBytes(byteArrayOf(0, 7, rnd.nextInt(100).toByte()))
            }
            git.add().addFilepattern(".").call()
            git.add().addFilepattern(".").setUpdate(true).call()
            if (git.status().call().hasUncommittedChanges()) {
                git.commit().setAuthor(pcIdent).setCommitter(pcIdent).setMessage("PC edit").call()
                val update = git.push().setRemote("origin").add("main").call()
                    .firstNotNullOf { it.getRemoteUpdate("refs/heads/main") }
                check(update.status == org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK) {
                    "PC push was turned down: ${update.status}"
                }
            }
        }
        checkRemoteMovedForward("PC push")
    }

    /** `git pull --ff-only`, as the PC's scheduled task runs it. It must always work. */
    private fun pcPull() {
        Git.open(pcDir).use { git ->
            val result = git.pull().setFastForward(MergeCommand.FastForwardMode.FF_ONLY).call()
            check(result.isSuccessful) { "PC could not pull --ff-only: ${result.mergeResult?.mergeStatus}" }
        }
    }

    private fun writePc(path: String, text: String) {
        val f = File(pcDir, path)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    // ---- Edits ----

    private fun newToken(side: String): String = "$side${nextToken++}".also { created += it }

    private fun editTree(
        paths: List<String>,
        side: String,
        read: (String) -> String?,
        write: (String, String) -> Unit,
        delete: (String) -> Unit,
    ) {
        val choice = rnd.nextInt(100)
        when {
            choice < 8 || paths.isEmpty() -> {
                // A new note; sometimes at a path the other side may also create.
                val path = "notes/new${rnd.nextInt(4)}.md"
                val old = read(path)
                if (old != null) removeTokens(old)
                write(path, newToken(side) + "\n")
            }
            choice < 13 -> {
                val path = paths[rnd.nextInt(paths.size)]
                read(path)?.let { removeTokens(it) }
                delete(path)
            }
            else -> {
                // Most edits go to two busy notes, so the phone and the PC often touch the same lines.
                val busy = paths.filter { it == "notes/a.md" || it == "notes/b.md" }
                val pool = if (busy.isNotEmpty() && rnd.nextInt(3) > 0) busy else paths
                val path = pool[rnd.nextInt(pool.size)]
                val text = read(path) ?: return
                write(path, editText(text, side))
            }
        }
    }

    /** Inserts, replaces, or deletes one line. Removed tokens are recorded as removed. */
    private fun editText(text: String, side: String): String {
        val lines = text.lines().let { if (it.lastOrNull() == "") it.dropLast(1) else it }.toMutableList()
        when (if (lines.isEmpty()) 0 else rnd.nextInt(3)) {
            0 -> lines.add(rnd.nextInt(lines.size + 1), newToken(side))
            1 -> {
                val i = rnd.nextInt(lines.size)
                removeTokens(lines[i])
                lines[i] = newToken(side)
            }
            else -> removeTokens(lines.removeAt(rnd.nextInt(lines.size)))
        }
        return lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n")
    }

    private fun removeTokens(text: String) {
        TOKEN.findAll(text).forEach { removed += it.value }
    }

    // ---- Checks ----

    private fun remoteHead(): ObjectId? = Git.open(remoteDir).use { it.repository.resolve("refs/heads/main") }

    private fun checkRemoteMovedForward(context: String) {
        val head = remoteHead() ?: return
        val last = lastRemoteHead
        if (last != null && last != head) {
            Git.open(remoteDir).use { git ->
                RevWalk(git.repository).use { walk ->
                    check(walk.isMergedInto(walk.parseCommit(last), walk.parseCommit(head))) {
                        "$context: GitHub's main moved backwards or sideways (a force push)"
                    }
                }
            }
        }
        lastRemoteHead = head
    }

    private fun checkConverged() {
        val phone = snapshot(phoneDir)
        val pc = snapshot(pcDir)
        check(phone == pc) {
            val differing = (phone.keys + pc.keys).filter { phone[it] != pc[it] }
            "the phone and the PC ended with different files: $differing"
        }
        Git.open(phoneDir).use { git ->
            check(git.repository.resolve("HEAD") == remoteHead()) { "the phone's main is not GitHub's" }
        }
        val present = phone.values.flatMap { text -> TOKEN.findAll(text).map { it.value } }.toSet()
        val lost = created - removed - present
        check(lost.isEmpty()) { "tokens lost: ${lost.sorted()}" }
        report.tokensAlive = present.size
        report.tokensRemoved = removed.size
    }

    /** Every file but `.git`, byte for byte (Latin-1 keeps each byte as one char). */
    private fun snapshot(dir: File): Map<String, String> =
        dir.walkTopDown()
            .onEnter { it.name != ".git" }
            .filter { it.isFile }
            .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes().toString(Charsets.ISO_8859_1) }

    companion object {
        private val TOKEN = Regex("""\b[IPC]\d+\b""")

        /** Keeps the PC's own git settings (such as autocrlf) out of the harness's repos. */
        fun isolateGitConfig(dir: File) {
            dir.mkdirs()
            val base = SystemReader.getInstance()
            SystemReader.setInstance(object : SystemReader() {
                override fun getHostname() = "harness"
                override fun getenv(variable: String?): String? = null
                override fun getProperty(key: String?): String? = base.getProperty(key)
                override fun openUserConfig(parent: Config?, fs: FS?) =
                    FileBasedConfig(parent, File(dir, "user.gitconfig"), fs)
                override fun openSystemConfig(parent: Config?, fs: FS?) =
                    FileBasedConfig(parent, File(dir, "system.gitconfig"), fs)
                override fun openJGitConfig(parent: Config?, fs: FS?) =
                    FileBasedConfig(parent, File(dir, "jgit.config"), fs)
                override fun getCurrentTime() = base.currentTime
                override fun getTimezone(time: Long) = base.getTimezone(time)
            })
        }
    }
}
