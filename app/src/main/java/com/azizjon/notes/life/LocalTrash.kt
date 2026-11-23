package com.azizjon.notes.life

import java.io.File

/** A note deleted on this phone, kept so it can be put back. */
data class TrashEntry(val id: String, val path: String, val title: String, val deletedAt: Long)

/**
 * Notes deleted on this phone, kept on the phone so they can be restored with one tap. The
 * deletion still reaches GitHub (and the note stays in git history there).
 */
class LocalTrash(private val dir: File) {

    fun keep(path: String, text: String) {
        val now = System.currentTimeMillis()
        val id = "$now-${path.hashCode().toUInt()}"
        dir.mkdirs()
        File(dir, "$id.md").writeText(text)
        File(dir, "$id.path").writeText(path)
    }

    fun list(): List<TrashEntry> =
        dir.listFiles { f -> f.name.endsWith(".path") }.orEmpty().mapNotNull { pathFile ->
            val id = pathFile.name.removeSuffix(".path")
            val text = File(dir, "$id.md").takeIf { it.isFile }?.readText() ?: return@mapNotNull null
            val path = pathFile.readText()
            TrashEntry(id, path, noteTitle(path, text), id.substringBefore('-').toLongOrNull() ?: 0L)
        }.sortedByDescending { it.deletedAt }

    fun text(entry: TrashEntry): String? = File(dir, "${entry.id}.md").takeIf { it.isFile }?.readText()

    fun remove(entry: TrashEntry) {
        File(dir, "${entry.id}.md").delete()
        File(dir, "${entry.id}.path").delete()
    }

    fun clear() {
        dir.deleteRecursively()
    }
}
