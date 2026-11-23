package com.azizjon.notes.life

import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Runs the clash harness on the PC over many random seeds. Set `-Pclash.seeds=500` style counts
 * with the system properties `clash.seeds` and `clash.rounds` for a longer run.
 */
class ClashHarnessTest {

    @Test
    fun clashingEditsLoseNothing() {
        val seeds = System.getProperty("clash.seeds")?.toInt() ?: 40
        val rounds = System.getProperty("clash.rounds")?.toInt() ?: 150
        val root = Files.createTempDirectory("clash").toFile()
        ClashHarness.isolateGitConfig(File(root, "config"))
        try {
            val totals = ClashHarness.Report()
            for (seed in 1..seeds) {
                val report = try {
                    ClashHarness(File(root, "run$seed"), seed.toLong()).run(rounds)
                } catch (e: Throwable) {
                    throw AssertionError("seed $seed: ${e.message}", e)
                }
                totals.add(report)
            }
            println("clash harness, $seeds seeds x $rounds rounds: $totals")
        } finally {
            root.deleteRecursively()
        }
    }
}

private fun ClashHarness.Report.add(other: ClashHarness.Report) {
    phoneEdits += other.phoneEdits
    pcEdits += other.pcEdits
    editorSaves += other.editorSaves
    syncs += other.syncs
    crashes += other.crashes
    partialCheckouts += other.partialCheckouts
    pushesDuringSync += other.pushesDuringSync
    staleLocks += other.staleLocks
    conflictFiles += other.conflictFiles
    keptBoth += other.keptBoth
    tokensAlive += other.tokensAlive
    tokensRemoved += other.tokensRemoved
}
