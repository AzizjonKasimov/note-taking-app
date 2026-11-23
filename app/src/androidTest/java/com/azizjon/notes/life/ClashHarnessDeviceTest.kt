package com.azizjon.notes.life

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The clash harness on a device, so JGit's merges, file writes, and recovery are checked on
 * Android's own runtime and file system. Run it with `am instrument` (see the project's notes);
 * `-e seeds N -e rounds N` set its size.
 */
@RunWith(AndroidJUnit4::class)
class ClashHarnessDeviceTest {

    @Test
    fun clashingEditsLoseNothingOnTheDevice() {
        val args = InstrumentationRegistry.getArguments()
        val seeds = args.getString("seeds")?.toInt() ?: 10
        val rounds = args.getString("rounds")?.toInt() ?: 150
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "clash")
        root.deleteRecursively()
        ClashHarness.isolateGitConfig(File(root, "config"))
        val started = System.currentTimeMillis()
        val totals = StringBuilder()
        try {
            for (seed in 1..seeds) {
                val report = try {
                    ClashHarness(File(root, "run$seed"), seed.toLong()).run(rounds)
                } catch (e: Throwable) {
                    throw AssertionError("seed $seed: ${e.message}", e)
                }
                File(root, "run$seed").deleteRecursively()
                totals.append("seed $seed: $report\n")
            }
        } finally {
            root.deleteRecursively()
        }
        val seconds = (System.currentTimeMillis() - started) / 1000
        Log.i(TAG, "clash harness on the device, $seeds seeds x $rounds rounds in ${seconds}s:\n$totals")
    }

    private companion object {
        const val TAG = "Clash"
    }
}
