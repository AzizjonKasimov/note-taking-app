package com.azizjon.notes.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.azizjon.notes.NotesApplication
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * The alarms at 08:00 and 20:00, from OneShot Reminder's scheduler: exact while the app may set
 * exact alarms (Android grants USE_EXACT_ALARM at install, from Android 13), else as close as
 * Android allows, both even while the phone sleeps.
 */
object ReminderAlarms {
    private const val ACTION_ALARM = "com.azizjon.notes.action.REMINDER_ALARM"
    private const val MORNING_CODE = 1
    private const val EVENING_CODE = 2

    /** Sets the next morning and evening alarms when [on], else cancels them. */
    fun schedule(context: Context, on: Boolean, now: ZonedDateTime = ZonedDateTime.now()) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        for ((code, time) in listOf(MORNING_CODE to Reminders.MORNING, EVENING_CODE to Reminders.EVENING)) {
            if (!on) {
                pending(context, code, PendingIntent.FLAG_NO_CREATE)?.let {
                    alarms.cancel(it)
                    it.cancel()
                }
                continue
            }
            val operation = pending(context, code, PendingIntent.FLAG_UPDATE_CURRENT) ?: continue
            val at = next(now, time).toInstant().toEpochMilli()
            if (canScheduleExact(context)) {
                try {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
                    continue
                } catch (_: SecurityException) {
                }
            }
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        }
    }

    /** The next [time] after [now]: today's if it is still ahead, else tomorrow's. */
    fun next(now: ZonedDateTime, time: LocalTime): ZonedDateTime {
        val today = now.toLocalDate().atTime(time).atZone(now.zone)
        return if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(time).atZone(now.zone)
    }

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun pending(context: Context, code: Int, flag: Int): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_ALARM)
        return PendingIntent.getBroadcast(context, code, intent, flag or PendingIntent.FLAG_IMMUTABLE)
    }
}

/**
 * Wakes for the alarms, and after a restart, an app update, or a change of the clock or the time
 * zone (Android drops alarms on a restart), from OneShot Reminder's receivers.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? NotesApplication ?: return
        val pending = goAsync()
        app.appScope.launch {
            try {
                app.reminders.onAlarm()
            } catch (e: Exception) {
                Log.w("Reminders", "A reminder failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * The morning upkeep, after taking in GitHub's changes when online (so it works on the newest
 * list, as the bot did), and the reminders still showing brought up to date. Runs once a day, after
 * the morning alarm or when the app opens.
 */
class ReminderUpkeepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as NotesApplication
        if (!app.life.isCloned()) return Result.success()
        app.life.sync() // without a connection this fails fast, and the upkeep works offline
        app.reminders.upkeepIfDue()
        app.reminders.refreshShown()
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "reminders-upkeep",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ReminderUpkeepWorker>().build(),
            )
        }
    }
}
