package com.azizjon.notes.assistant

import com.azizjon.notes.life.ClashHarness
import com.azizjon.notes.life.FileEdit
import com.azizjon.notes.life.NoteFiles
import com.azizjon.notes.life.RepoCommit
import java.io.File
import java.nio.file.Files
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent

/** The assistant's tools on a plain folder with a git repo in it, as the app runs them on the clone. */
class FolderRepo(val root: File) : AssistantRepo {
    val files = NoteFiles(root)
    private val who = PersonIdent("Phone", "phone@example.com")

    override suspend fun <T> read(block: (NoteFiles) -> T): T = synchronized(this) { block(files) }

    override suspend fun commitEdit(edit: (read: (String) -> String?) -> FileEdit?): Set<String> = synchronized(this) {
        val change = edit { files.read(it) } ?: return emptySet()
        RepoCommit.apply(files, change.message, change.files, who)
    }

    /** Commit messages, newest first. */
    fun log(): List<String> = Git.open(root).use { git -> git.log().call().map { it.fullMessage.trim() } }

    /** Paths git sees as changed and not committed. */
    fun uncommitted(): Set<String> = Git.open(root).use { git ->
        git.status().call().let { it.modified + it.untracked + it.added + it.changed + it.missing + it.removed }
    }

    fun text(path: String): String? = files.read(path)

    companion object {
        private var isolated = false

        /** Keeps the PC's own git settings (such as line-ending conversion) out of the tests. */
        fun isolateGitConfig() {
            if (!isolated) {
                ClashHarness.isolateGitConfig(Files.createTempDirectory("assistant-git").toFile())
                isolated = true
            }
        }

        /** A new repo in a temporary folder holding [files], committed. */
        fun create(files: Map<String, String>): FolderRepo {
            isolateGitConfig()
            val dir = Files.createTempDirectory("assistant-repo").toFile()
            Git.init().setDirectory(dir).setInitialBranch("main").call().use { git ->
                files.forEach { (path, text) -> File(dir, path).apply { parentFile.mkdirs() }.writeText(text) }
                git.add().addFilepattern(".").call()
                val who = PersonIdent("PC", "pc@example.com")
                git.commit().setAuthor(who).setCommitter(who).setMessage("Start").call()
            }
            return FolderRepo(dir)
        }
    }
}
