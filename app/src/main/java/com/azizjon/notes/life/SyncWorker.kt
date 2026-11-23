package com.azizjon.notes.life

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.azizjon.notes.NotesApplication
import java.util.concurrent.TimeUnit

/**
 * Syncs in the background: soon after edits and when the app goes to the background (waiting for
 * a connection if there is none, so offline edits go up later), and every 30 minutes.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val life = (applicationContext as NotesApplication).life
        return when (life.sync()) {
            SyncResult.DONE -> Result.success()
            SyncResult.RETRY -> if (runAttemptCount < 20) Result.retry() else Result.failure()
            SyncResult.STOP -> Result.failure()
        }
    }

    companion object {
        private const val SOON = "life-sync-soon"
        private const val PERIODIC = "life-sync-periodic"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** A sync in [delaySeconds], unless one is already waiting (it will take these edits too). */
        fun syncSoon(context: Context, delaySeconds: Long) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.KEEP, request)
        }

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(online)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(SOON)
                cancelUniqueWork(PERIODIC)
            }
        }
    }
}
