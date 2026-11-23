package com.azizjon.notes.life

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** What an editor's save left on disk. */
data class EditorWrite(
    /** The file's text now. Differs from what the editor sent when a sync had changed the file. */
    val diskText: String,
    /** True when the editor's text was merged with changes that arrived from GitHub. */
    val merged: Boolean,
    /** True when both changed the same lines, so both versions sit between conflict markers. */
    val conflicted: Boolean,
)

/**
 * The Markdown files of the life repo's working tree: listing, reading, and safe writes.
 *
 * Plain file access with no git in it, so the sync and the editor agree on one set of rules.
 * Paths are relative to the repo root and use `/`.
 */
class NoteFiles(val root: File) {

    private val tmpDir: File get() = File(root, ".git/notes-tmp")

    fun file(path: String): File {
        require(isSafePath(path)) { "Not a note path: $path" }
        return File(root, path)
    }

    fun exists(path: String): Boolean = file(path).isFile

    fun read(path: String): String? {
        val f = file(path)
        return if (f.isFile) f.readText(Charsets.UTF_8) else null
    }

    /**
     * Writes the whole file at once: the text goes to a temporary file inside `.git` (never
     * committed) and is then moved over the note, so a crash leaves the old or the new text, never
     * half of it.
     */
    fun write(path: String, text: String) {
        val target = file(path)
        target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Cannot create ${it.path}") }
        tmpDir.mkdirs()
        val tmp = File.createTempFile("note", ".tmp", tmpDir)
        try {
            tmp.outputStream().use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            tmp.delete()
        }
    }

    /** Deletes the note, and any folders it leaves empty. */
    fun delete(path: String): Boolean {
        val f = file(path)
        if (!f.isFile) return false
        if (!f.delete()) throw IOException("Cannot delete $path")
        var dir = f.parentFile
        while (dir != null && dir != root && dir.list()?.isEmpty() == true) {
            dir.delete()
            dir = dir.parentFile
        }
        return true
    }

    /**
     * Saves an editor's text. [base] is the text the editor started from or last saved. When the
     * file changed on disk since then (a sync brought in the PC's edits), the editor's changes are
     * merged into it, so neither side's edits are lost. A note deleted elsewhere while it was open
     * is written again with the editor's text.
     */
    fun writeFromEditor(path: String, text: String, base: String?): EditorWrite {
        val disk = read(path)
        val result = when {
            disk == null || disk == text -> EditorWrite(text, merged = false, conflicted = false)
            disk == base -> EditorWrite(text, merged = false, conflicted = false)
            else -> {
                val merged = TextMerge.merge(base, text, disk)
                EditorWrite(merged.text, merged = merged.text != text, conflicted = merged.conflicted)
            }
        }
        if (result.diskText != disk) write(path, result.diskText)
        return result
    }

    /** Every Markdown file, skipping `.git` and other hidden folders. */
    fun listMarkdown(): List<String> {
        val out = ArrayList<String>()
        fun walk(dir: File, prefix: String) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                val name = child.name
                if (name.startsWith(".")) continue
                if (child.isDirectory) {
                    if (name in SKIPPED_FOLDERS) continue
                    walk(child, "$prefix$name/")
                } else if (name.endsWith(".md", ignoreCase = true)) {
                    out += "$prefix$name"
                }
            }
        }
        walk(root, "")
        return out
    }

    /** A free path for a new note in [folder] ("" for the repo root), named by the time it was made. */
    fun newNotePath(folder: String, now: LocalDateTime = LocalDateTime.now()): String {
        val stem = now.format(NEW_NOTE_NAME)
        val dir = if (folder.isEmpty()) "" else "${folder.trimEnd('/')}/"
        var candidate = "$dir$stem.md"
        var n = 2
        while (file(candidate).exists()) candidate = "$dir$stem-${n++}.md"
        return candidate
    }

    /** A free path in the same folder named after [title], or null when the title makes no name. */
    fun pathForTitle(path: String, title: String): String? {
        val slug = slugify(title)
        if (slug.isEmpty()) return null
        val dir = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val candidate = "$dir$slug.md"
        if (candidate == path) return null
        return candidate.takeUnless { file(it).exists() }
    }

    fun move(from: String, to: String) {
        val source = file(from)
        val target = file(to)
        if (target.exists()) throw IOException("$to already exists")
        target.parentFile?.mkdirs()
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        private val SKIPPED_FOLDERS = setOf("node_modules", "__pycache__")
        private val NEW_NOTE_NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")

        /** A relative path inside the repo that is not git's own folder. */
        fun isSafePath(path: String): Boolean {
            if (path.isBlank() || path.startsWith("/") || path.contains('\\')) return false
            val parts = path.split('/')
            return parts.none { it.isEmpty() || it == "." || it == ".." } && parts.first() != ".git"
        }

        /** "Gift ideas for May!" -> "gift-ideas-for-may". Keeps letters of any script. */
        fun slugify(title: String): String =
            title.lowercase()
                .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
                .trim('-')
                .take(60)
                .trimEnd('-')
    }
}
