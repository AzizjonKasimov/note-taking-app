package com.azizjon.notes.assistant

import com.azizjon.notes.life.FileEdit
import com.azizjon.notes.reminders.ReminderPlan
import com.azizjon.notes.reminders.Reminders
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The assistant against the real AI gateway, on a throwaway copy of a repository: each message's
 * reply, time, and changes as diffs, so its rules can be tried without a phone. The source repo's
 * uncommitted `ASSISTANT.md` and `reminders/README.md` go into the copy, since rule edits are what
 * this is for. Nothing reaches the source repo or GitHub. Run it through bwenv, which gives it the
 * gateway key from Bitwarden:
 *
 *     bwenv .\gradlew.bat testDebugUnitTest --rerun --tests *AssistantLiveTest -Passistant.live=E:\path\to\repo
 *
 * `-Passistant.messages=<file>` takes the messages from a file, one per line, in place of the
 * built-in ones; each message sees the earlier ones, as in the app. `-Passistant.model=claude-haiku-5-5`
 * and `-Passistant.effort=low` try another model or thinking level than the app's first pick.
 * Through bwenv on Windows, PowerShell splits `-Passistant.live=E:\...` at the drive's colon, so give
 * the settings as environment variables instead, which Gradle reads as the same properties:
 * `${env:ORG_GRADLE_PROJECT_assistant.live} = 'E:\path\to\repo'`.
 */
class AssistantLiveTest {
    @Test
    fun liveTurns() = runBlocking {
        val source = System.getProperty("assistant.live")
        assumeTrue("Set -Passistant.live=<repo> to talk to the real gateway.", source != null)
        val token = System.getenv("GATEWAY_TOKEN")
        check(!token.isNullOrBlank()) { "Run this through bwenv, so that GATEWAY_TOKEN comes from Bitwarden." }
        val repo = copyOf(File(source!!))
        val messages = System.getProperty("assistant.messages")?.let { path -> File(path).readLines().filter { it.isNotBlank() } }
            ?: DEFAULT_MESSAGES
        val assistant = Assistant(
            gateway = GatewayClient(token = { token }),
            tools = AssistantTools(repo),
            repo = repo,
            afterTurn = { changes -> expand(repo, changes) },
            clock = ZonedDateTime::now,
        )
        val choice = AssistantChoice(
            model = System.getProperty("assistant.model")?.let { id -> AssistantModel.entries.single { it.id == id } }
                ?: AssistantChoice.DEFAULT.model,
            effort = System.getProperty("assistant.effort")?.let { id -> AssistantEffort.entries.single { it.id == id } }
                ?: AssistantChoice.DEFAULT.effort,
        )
        println("Running on ${choice.label}")
        val earlier = mutableListOf<AssistantPrompt.Earlier>()
        var total = 0.0
        for (message in messages) {
            val started = System.nanoTime()
            val steps = mutableListOf<String>()
            val outcome = assistant.run(message, earlier.takeLast(AssistantSession.MAX_EARLIER), TurnLog(), choice) { progress ->
                if (progress != Assistant.THINKING) steps += progress
            }
            val seconds = (System.nanoTime() - started) / 1e9
            total += seconds
            println("\n=== \"$message\"  (%.1f s, %d steps)".format(seconds, steps.size))
            steps.forEach { println("    . $it") }
            outcome.error?.let { println("  ERROR: $it") }
            outcome.reply?.let { println("  Reply: $it") }
            for (change in outcome.changes) {
                println("  Change: ${change.summary}")
                change.files.forEach { f -> println(diff(f).prependIndent("      ")) }
            }
            earlier += AssistantPrompt.Earlier(message, outcome.reply, outcome.changes.map { it.summary })
        }
        println("\n${messages.size} messages in %.0f s; the copy is at ${repo.root}".format(total))
    }

    private fun copyOf(source: File): FolderRepo {
        FolderRepo.isolateGitConfig()
        val dir = Files.createTempDirectory("assistant-live").toFile()
        Git.cloneRepository().setURI(source.toURI().toString()).setDirectory(dir).call().use { git ->
            val rules = listOf(AssistantPrompt.RULES_FILE, AssistantPrompt.LIST_RULES_FILE).filter { File(source, it).isFile }
            rules.forEach { File(source, it).copyTo(File(dir, it), overwrite = true) }
            git.add().apply { rules.forEach { addFilepattern(it) } }.call()
            if (!git.status().call().isClean) {
                val who = org.eclipse.jgit.lib.PersonIdent("PC", "pc@example.com")
                git.commit().setAuthor(who).setCommitter(who).setMessage("The rules being tried").call()
            }
        }
        return FolderRepo(dir)
    }

    /** What the app does after a turn that changed the list: add new events' checklist steps. */
    private suspend fun expand(repo: FolderRepo, changes: List<AssistantChange>): List<AssistantChange> {
        if (changes.none { Reminders.UPCOMING in it.paths }) return emptyList()
        val checklists = ReminderPlan.checklists(
            repo.read { files ->
                files.file(Reminders.CHECKLISTS).listFiles { f -> f.name.endsWith(".md") }.orEmpty()
                    .associate { it.name to it.readText() }
            },
        )
        var change: AssistantChange? = null
        repo.commitEdit { read ->
            val text = read(Reminders.UPCOMING) ?: return@commitEdit null
            val (after, added) = ReminderPlan.expand(text, checklists, LocalDate.now())
            val real = added.filterNot { it.startsWith("warning:") }
            if (real.isEmpty() || after == text) return@commitEdit null
            change = AssistantChange(real.joinToString("; "), listOf(FileChange(Reminders.UPCOMING, text, after)))
            FileEdit("Reminders: " + real.joinToString("; "), mapOf(Reminders.UPCOMING to after))
        }
        return listOfNotNull(change)
    }

    private fun diff(f: FileChange): String {
        val a = RawText((f.before ?: "").toByteArray())
        val b = RawText((f.after ?: "").toByteArray())
        val edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM).diff(RawTextComparator.DEFAULT, a, b)
        val out = ByteArrayOutputStream()
        DiffFormatter(out).apply { setContext(1) }.format(edits, a, b)
        return "${f.path}${if (f.before == null) " (new)" else ""}\n" + out.toString(Charsets.UTF_8.name()).trimEnd()
    }

    private companion object {
        val DEFAULT_MESSAGES = listOf(
            "Remind me to call the bank on Monday about my new card",
            "Actually make it Tuesday",
            "Idea: an app that tells me which bus to take when it rains",
            "The bakery by the station closes at 6 on weekdays",
            "What do I have this Saturday?",
        )
    }
}
