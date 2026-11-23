package com.azizjon.notes.life

import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.TreeFilter
import java.io.File

/**
 * When each file last changed, from git history, so notes sort newest first. Files checked out by
 * a clone all have the same file time, so history is the only real "last changed".
 *
 * Saved in [cacheFile] with the commit it was worked out for; later calls walk only newer commits.
 */
class ChangeTimes(private val cacheFile: File) {
    private var head: ObjectId? = null
    private var times: Map<String, Long> = emptyMap()

    init {
        load()
    }

    /** path -> last commit time in milliseconds, up to [repo]'s HEAD. */
    fun update(repo: Repository): Map<String, Long> {
        val current = repo.resolve("HEAD") ?: return times
        if (current == head) return times
        val found = HashMap<String, Long>()
        RevWalk(repo).use { walk ->
            walk.sort(RevSort.COMMIT_TIME_DESC)
            walk.markStart(walk.parseCommit(current))
            val known = head
            if (known != null && repo.objectDatabase.has(known)) {
                runCatching { walk.markUninteresting(walk.parseCommit(known)) }
            }
            TreeWalk(repo).use { tw ->
                tw.isRecursive = true
                for (commit in walk) {
                    tw.reset()
                    if (commit.parentCount > 0) {
                        tw.addTree(walk.parseCommit(commit.getParent(0)).tree)
                    } else {
                        tw.addTree(EmptyTreeIterator())
                    }
                    tw.addTree(commit.tree)
                    tw.filter = TreeFilter.ANY_DIFF
                    val time = commit.commitTime * 1000L
                    while (tw.next()) found.putIfAbsent(tw.pathString, time)
                }
            }
        }
        times = times + found
        head = current
        save()
        return times
    }

    private fun load() {
        val lines = cacheFile.takeIf { it.isFile }?.readLines() ?: return
        val first = lines.firstOrNull() ?: return
        head = runCatching { ObjectId.fromString(first) }.getOrNull() ?: return
        times = lines.drop(1).mapNotNull { line ->
            val tab = line.lastIndexOf('\t')
            if (tab <= 0) null else line.substring(0, tab) to (line.substring(tab + 1).toLongOrNull() ?: return@mapNotNull null)
        }.toMap()
    }

    private fun save() {
        val tmp = File(cacheFile.path + ".tmp")
        tmp.parentFile?.mkdirs()
        tmp.bufferedWriter().use { out ->
            out.write(head?.name ?: "")
            out.write("\n")
            for ((path, time) in times) {
                out.write(path)
                out.write("\t")
                out.write(time.toString())
                out.write("\n")
            }
        }
        if (!tmp.renameTo(cacheFile)) {
            cacheFile.delete()
            tmp.renameTo(cacheFile)
        }
    }

    fun clear() {
        head = null
        times = emptyMap()
        cacheFile.delete()
    }
}
