package com.azizjon.notes.life

import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Runs the clash harness on the PC over many random seeds. Set `-Pclash.seeds=500` style counts
 * with the system properties `clash.seeds` and `clash.rounds` for a longer run, and `clash.from`
 * to start at a later seed (`-Pclash.from=26 -Pclash.seeds=26` runs seed 26 alone). A failed seed's
 * repos stay on disk, and the failure names their folder.
 */
class ClashHarnessTest {

    @Test
    fun clashingEditsLoseNothing() {
        val first = System.getProperty("clash.from")?.toInt() ?: 1
        val seeds = System.getProperty("clash.seeds")?.toInt() ?: 40
        val rounds = System.getProperty("clash.rounds")?.toInt() ?: 150
        val root = Files.createTempDirectory("clash").toFile()
        ClashHarness.isolateGitConfig(File(root, "config"))
        var keep = false
        try {
            val totals = ClashHarness.Report()
            for (seed in first..seeds) {
                val dir = File(root, "run$seed")
                val report = try {
                    ClashHarness(dir, seed.toLong()).run(rounds)
                } catch (e: Throwable) {
                    keep = true
                    throw AssertionError("seed $seed (its repos are in $dir): ${e.message}", e)
                }
                totals.add(report)
            }
            println("clash harness, seeds $first to $seeds x $rounds rounds: $totals")
        } finally {
            if (!keep) root.deleteRecursively()
        }
    }
}

private fun ClashHarness.Report.add(other: ClashHarness.Report) {
    phoneEdits += other.phoneEdits
    pcEdits += other.pcEdits
    editorSaves += other.editorSaves
    commitEdits += other.commitEdits
    syncs += other.syncs
    crashes += other.crashes
    partialCheckouts += other.partialCheckouts
    pushesDuringSync += other.pushesDuringSync
    editsDuringSync += other.editsDuringSync
    staleLocks += other.staleLocks
    conflictFiles += other.conflictFiles
    keptBoth += other.keptBoth
    tokensAlive += other.tokensAlive
    tokensRemoved += other.tokensRemoved
}
