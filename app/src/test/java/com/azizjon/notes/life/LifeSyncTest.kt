package com.azizjon.notes.life

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.URIish
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The sync's cases one at a time; [ClashHarnessTest] mixes them at random. */
class LifeSyncTest {
    private lateinit var root: File
    private lateinit var remote: File
    private lateinit var pc: File
    private lateinit var phone: File
    private val ident = PersonIdent("Test", "test@example.invalid")

    @Before
    fun setUp() {
        root = Files.createTempDirectory("lifesync").toFile()
        ClashHarness.isolateGitConfig(File(root, "config"))
        remote = File(root, "github.git")
        pc = File(root, "pc")
        phone = File(root, "phone")
        Git.init().setBare(true).setDirectory(remote).setInitialBranch("main").call().close()
        Git.init().setDirectory(pc).setInitialBranch("main").call().use { git ->
            File(pc, "notes").mkdirs()
            File(pc, "notes/a.md").writeText("one\ntwo\nthree\nfour\n")
            File(pc, "notes/b.md").writeText("b\n")
            git.add().addFilepattern(".").call()
            git.commit().setAuthor(ident).setCommitter(ident).setMessage("Start").call()
            git.remoteAdd().setName("origin").setUri(URIish(remote.absolutePath)).call()
            git.push().setRemote("origin").add("main").call()
            git.repository.config.apply {
                setString("branch", "main", "remote", "origin")
                setString("branch", "main", "merge", "refs/heads/main")
            }.save()
        }
        LifeSync.clone(remote.absolutePath, phone, credentials = null)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun sync(hooks: SyncHooks = SyncHooks { _, _ -> }) =
        LifeSync(phone, credentials = null, identity = { ident }, hooks = hooks).sync()

    private fun pcCommit(path: String, text: String?) {
        Git.open(pc).use { git ->
            git.pull().setFastForward(MergeCommand.FastForwardMode.FF_ONLY).call()
            val f = File(pc, path)
            if (text == null) f.delete() else f.writeText(text)
            git.add().addFilepattern(".").call()
            git.add().addFilepattern(".").setUpdate(true).call()
            git.commit().setAuthor(ident).setCommitter(ident).setMessage("PC").call()
            git.push().setRemote("origin").add("main").call()
        }
    }

    private fun pcPull(): String {
        Git.open(pc).use { git ->
            val result = git.pull().setFastForward(MergeCommand.FastForwardMode.FF_ONLY).call()
            assertTrue("pull --ff-only failed", result.isSuccessful)
        }
        return File(pc, "notes/a.md").readText()
    }

    private val files get() = NoteFiles(phone)

    @Test
    fun thePhonesEditReachesThePc() {
        files.write("notes/a.md", "one\ntwo\nthree\nfour\nfive\n")
        val outcome = sync()
        assertEquals(listOf("notes/a.md"), outcome.phoneChanges)
        assertTrue(outcome.pushed)
        assertEquals("one\ntwo\nthree\nfour\nfive\n", pcPull())
        Git.open(remote).use { git ->
            assertEquals("Edit notes/a.md on the phone", git.log().setMaxCount(1).call().single().fullMessage)
        }
    }

    @Test
    fun thePcsEditReachesThePhone() {
        pcCommit("notes/a.md", "zero\none\ntwo\nthree\nfour\n")
        val outcome = sync()
        assertTrue(outcome.pulled)
        assertFalse(outcome.pushed)
        assertEquals(setOf("notes/a.md"), outcome.changedPaths)
        assertEquals("zero\none\ntwo\nthree\nfour\n", files.read("notes/a.md"))
    }

    @Test
    fun editsToDifferentLinesAreCombinedOnAStraightHistory() {
        pcCommit("notes/a.md", "one\ntwo\nthree\nfour (pc)\n")
        files.write("notes/a.md", "one (phone)\ntwo\nthree\nfour\n")
        val outcome = sync()
        assertTrue(outcome.pushed)
        assertTrue(outcome.conflicts.isEmpty())
        assertEquals("one (phone)\ntwo\nthree\nfour (pc)\n", pcPull())
        Git.open(remote).use { git ->
            val head = git.log().setMaxCount(1).call().single()
            assertEquals("the phone's commit sits on top of GitHub's, not beside it", 1, head.parentCount)
        }
    }

    @Test
    fun thePhonesOwnCommitMessagesSurviveAMerge() {
        fun phoneCommit(text: String, message: String) {
            files.write("notes/a.md", text)
            Git.open(phone).use { git ->
                git.add().addFilepattern("notes/a.md").call()
                git.commit().setAuthor(ident).setCommitter(ident).setOnly("notes/a.md").setMessage(message).call()
            }
        }
        fun githubMessage() = Git.open(remote).use { it.log().setMaxCount(1).call().single().fullMessage }

        phoneCommit("one (phone)\ntwo\nthree\nfour\n", "Reminders: done: one")
        pcCommit("notes/b.md", "b (pc)\n")
        sync()
        assertEquals("Reminders: done: one", githubMessage())

        phoneCommit("one (phone)\ntwo (phone)\nthree\nfour\n", "Reminders: done: two")
        phoneCommit("one (phone)\ntwo (phone)\nthree (phone)\nfour\n", "Reminders: skipped: three\n\nWith a body.")
        pcCommit("notes/b.md", "b (pc) again\n")
        sync()
        assertEquals(
            "Edit notes/a.md on the phone\n\n- Reminders: done: two\n- Reminders: skipped: three",
            githubMessage(),
        )
        assertEquals("one (phone)\ntwo (phone)\nthree (phone)\nfour\n", pcPull())
    }

    @Test
    fun theSameLineChangedOnBothSidesKeepsBoth() {
        pcCommit("notes/a.md", "one\nTWO (pc)\nthree\nfour\n")
        files.write("notes/a.md", "one\nTWO (phone)\nthree\nfour\n")
        val outcome = sync()
        assertEquals(listOf("notes/a.md"), outcome.conflicts)
        val text = pcPull()
        assertTrue(text, text.contains("TWO (phone)") && text.contains("TWO (pc)"))
        assertTrue(TextMerge.hasConflict(text))
    }

    @Test
    fun aNoteDeletedOnOneSideAndEditedOnTheOtherIsKept() {
        pcCommit("notes/b.md", null)
        files.write("notes/b.md", "b, edited on the phone\n")
        sync()
        pcPull()
        assertEquals("b, edited on the phone\n", File(pc, "notes/b.md").readText())

        files.delete("notes/a.md")
        pcCommit("notes/a.md", "one\ntwo\nthree\nfour\nfive (pc)\n")
        sync()
        assertEquals("one\ntwo\nthree\nfour\nfive (pc)\n", files.read("notes/a.md"))
    }

    @Test
    fun aSyncKilledWhileMovingTheBranchIsFinishedNextTime() {
        pcCommit("notes/a.md", "one\ntwo\nthree\nfour (pc)\n")
        files.write("notes/b.md", "b (phone)\n")
        try {
            sync { step, _ -> if (step == SyncStep.CHECKED_OUT) throw IllegalStateException("killed") }
        } catch (_: IllegalStateException) {
        }
        LifeSync(phone, credentials = null, identity = { ident }).recover()
        Git.open(phone).use { git -> assertTrue(git.status().call().isClean) }
        assertTrue(sync().pushed)
        pcPull()
        assertEquals("b (phone)\n", File(pc, "notes/b.md").readText())
        assertEquals("one\ntwo\nthree\nfour (pc)\n", File(pc, "notes/a.md").readText())
    }

    @Test
    fun aPushTurnedDownBecauseThePcPushedIsRetried() {
        files.write("notes/b.md", "b (phone)\n")
        var pushedFromPc = false
        val outcome = sync { step, _ ->
            if (step == SyncStep.BEFORE_PUSH && !pushedFromPc) {
                pushedFromPc = true
                pcCommit("notes/a.md", "one\ntwo\nthree\nfour (pc)\n")
            }
        }
        assertTrue(outcome.pushed)
        pcPull()
        assertEquals("b (phone)\n", File(pc, "notes/b.md").readText())
        Git.open(remote).use { git ->
            RevWalk(git.repository).use { walk ->
                val head = walk.parseCommit(git.repository.resolve("main"))
                assertEquals(1, head.parentCount)
            }
        }
    }
}
