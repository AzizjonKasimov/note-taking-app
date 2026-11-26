package com.azizjon.notes.life

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent

/**
 * Writes or deletes some files of a clone and commits just those paths, leaving anything else
 * changed in the working tree out of the commit. The caller holds the clone (no locking here), so
 * the app's repository service and the tests that run on a plain folder share one way to do it.
 */
object RepoCommit {
    /**
     * [changes] maps each path to its new text, or to null to delete the file. Returns the paths
     * whose files changed on disk. A file can end up back at its committed text (a tick undone
     * before any sync): git refuses a commit of only such files, and there is nothing to commit
     * for them, so none is made.
     */
    fun apply(files: NoteFiles, message: String, changes: Map<String, String?>, who: PersonIdent): Set<String> {
        val writes = changes.filter { (path, text) -> files.read(path) != text }
        if (writes.isEmpty()) return emptySet()
        writes.forEach { (path, text) -> if (text == null) files.delete(path) else files.write(path, text) }
        Git.open(files.root).use { git ->
            val written = writes.filterValues { it != null }.keys
            val deleted = writes.keys - written
            if (written.isNotEmpty()) git.add().apply { written.forEach { addFilepattern(it) } }.call()
            if (deleted.isNotEmpty()) git.rm().setCached(true).apply { deleted.forEach { addFilepattern(it) } }.call()
            val status = git.status().apply { writes.keys.forEach { addPath(it) } }.call()
            val changed = status.added + status.changed + status.removed
            if (changed.isNotEmpty()) {
                val commit = git.commit().setAuthor(who).setCommitter(who).setNoVerify(true).setMessage(message)
                changed.forEach { commit.setOnly(it) }
                commit.call()
            }
        }
        return writes.keys
    }
}
