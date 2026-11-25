package com.azizjon.notes.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.azizjon.notes.MainActivity
import com.azizjon.notes.R
import java.time.LocalDate

/**
 * The morning and evening reminders: one notification each, listing what the bot's message would
 * have (the same text, from [ReminderPlan]), which opens the Today screen when tapped. The way the
 * channel and permission are handled comes from OneShot Reminder.
 */
object ReminderNotifications {
    private const val CHANNEL_ID = "reminders"
    private const val TAG = "reminders"
    const val MORNING_ID = 1
    const val EVENING_ID = 2
    const val ACTION_OPEN_TODAY = "com.azizjon.notes.action.OPEN_TODAY"
    private val NUMBER = Regex("""^\d+\. """)

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "What is due each morning at 8:00, and what to prepare the evening before an event."
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Whether Notes may show notifications (Android 13 and later ask the user once). */
    fun canPost(context: Context): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** What is due [today]; nothing when no event, task, late task, or question is. */
    fun morning(context: Context, up: Upcoming, today: LocalDate, warnings: List<String>, alert: Boolean): Boolean {
        val d = ReminderPlan.digest(up, today)
        if (d.eventsToday.isEmpty() && d.today.isEmpty() && d.overdue.isEmpty() && d.questions.isEmpty()) {
            cancel(context, MORNING_ID)
            return false
        }
        val body = ReminderPlan.morningMessage(up, today, warnings, hint = null).lines().drop(1).dropWhile { it.isEmpty() }
        val late = d.overdue.size + d.hidden
        val summary = listOfNotNull(
            d.eventsToday.size.takeIf { it > 0 }?.let { if (it == 1) "1 event" else "$it events" },
            d.today.size.takeIf { it > 0 }?.let { "$it to do" },
            late.takeIf { it > 0 }?.let { "$it still open" },
            d.questions.size.takeIf { it > 0 }?.let { if (it == 1) "1 question" else "$it questions" },
        ).joinToString(" · ")
        return post(context, MORNING_ID, "Today, ${ReminderPlan.fmtDay(today)}", summary, bullets(body), alert)
    }

    /** What to prepare for tomorrow's events; nothing when none is on tomorrow. */
    fun evening(context: Context, up: Upcoming, today: LocalDate, alert: Boolean): Boolean {
        val text = ReminderPlan.eveningMessage(up, today, hint = null)
        if (text == null) {
            cancel(context, EVENING_ID)
            return false
        }
        val lines = text.lines()
        val body = lines.drop(1)
        val summary = body.firstOrNull { it.startsWith("Where: ") } ?: "What to prepare"
        return post(context, EVENING_ID, lines.first(), summary, bullets(body), alert)
    }

    /** The bot numbered items for replies; here they are bullets. */
    private fun bullets(lines: List<String>): String = lines.joinToString("\n") { it.replace(NUMBER, "• ") }

    @SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, title: String, summary: String, body: String, alert: Boolean): Boolean {
        createChannel(context)
        if (!canPost(context)) return false
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openToday(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setSilent(!alert)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(TAG, id, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun isShowing(context: Context, id: Int): Boolean =
        context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == TAG && it.id == id }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(TAG, id)

    fun cancelAll(context: Context) {
        cancel(context, MORNING_ID)
        cancel(context, EVENING_ID)
    }

    private fun openToday(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_TODAY
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
