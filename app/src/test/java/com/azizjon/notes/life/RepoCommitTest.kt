package com.azizjon.notes.life

import com.azizjon.notes.assistant.FolderRepo
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RepoCommitTest {
    private val who = PersonIdent("Phone", "phone@example.com")

    @Test
    fun commitsOnlyTheFilesItWritesOrDeletes() {
        val repo = FolderRepo.create(mapOf("a.md" to "a\n", "b.md" to "b\n", "c/d.md" to "d\n"))
        repo.files.write("b.md", "b, not saved yet\n")
        val written = RepoCommit.apply(repo.files, "Change a and d", mapOf("a.md" to "a2\n", "c/d.md" to null, "e/f.md" to "f\n"), who)
        assertEquals(setOf("a.md", "c/d.md", "e/f.md"), written)
        assertEquals(setOf("b.md"), repo.uncommitted())
        assertFalse(repo.files.root.resolve("c").exists())
        Git.open(repo.root).use { git ->
            val head = git.log().setMaxCount(1).call().first()
            assertEquals("Change a and d", head.fullMessage)
        }
    }

    @Test
    fun noCommitWhenAFileIsBackToItsCommittedText() {
        val repo = FolderRepo.create(mapOf("a.md" to "a\n"))
        repo.files.write("a.md", "a, ticked\n")
        RepoCommit.apply(repo.files, "Untick", mapOf("a.md" to "a\n"), who)
        assertEquals(listOf("Start"), repo.log())
        assertEquals(emptySet<String>(), repo.uncommitted())
    }

    @Test
    fun nothingToDoWhenTheTextIsTheSame() {
        val repo = FolderRepo.create(mapOf("a.md" to "a\n"))
        assertEquals(emptySet<String>(), RepoCommit.apply(repo.files, "Same", mapOf("a.md" to "a\n", "gone.md" to null), who))
        assertEquals(listOf("Start"), repo.log())
    }
}
