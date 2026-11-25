package com.azizjon.notes.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.azizjon.notes.BuildConfig
import com.azizjon.notes.NotesApplication
import com.azizjon.notes.reminders.ReminderAlarms
import com.azizjon.notes.reminders.ReminderNotifications
import kotlinx.coroutines.launch

/**
 * Settings for the reminders: on or off, and what keeps them from arriving on time (notifications
 * off, inexact alarms, battery restrictions), each with the system page that fixes it.
 */
@Composable
fun ReminderSettingsCard() {
    val context = LocalContext.current
    val reminders = (context.applicationContext as NotesApplication).reminders
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(reminders.enabled) }
    var canPost by remember { mutableStateOf(ReminderNotifications.canPost(context)) }
    var exact by remember { mutableStateOf(ReminderAlarms.canScheduleExact(context)) }
    var restricted by remember { mutableStateOf(isBackgroundRestricted(context)) }
    var debugNote by remember { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(Unit) {
        canPost = ReminderNotifications.canPost(context)
        exact = ReminderAlarms.canScheduleExact(context)
        restricted = isBackgroundRestricted(context)
        onPauseOrDispose { }
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Reminders", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Morning and evening reminders", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "At 8:00, what is due from your list (reminders/upcoming.md). At 20:00, what to prepare " +
                            "the evening before an event.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = on, onCheckedChange = {
                    on = it
                    reminders.enabled = it
                })
            }
            if (on && !canPost) {
                Problem("Notifications are off for Notes, so the reminders cannot show.") {
                    OutlinedButton(onClick = { context.open(appNotificationSettings(context)) }) { Text("Turn on notifications") }
                }
            }
            if (on && !exact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Problem("Android lets Notes set only inexact alarms, so a reminder may come some minutes late.") {
                    OutlinedButton(onClick = {
                        context.open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                    }) { Text("Allow exact alarms") }
                }
            }
            if (on && restricted) {
                Problem("Notes's battery use is restricted, so Android may hold the reminders back.") {
                    OutlinedButton(onClick = {
                        context.open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }) { Text("Open Notes's settings") }
                }
            }
            if (BuildConfig.DEBUG) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch { debugNote = if (reminders.postMorning()) null else "Nothing due today, or notifications are off." }
                    }) { Text("8:00 now") }
                    OutlinedButton(onClick = {
                        scope.launch { debugNote = if (reminders.postEvening()) null else "No event tomorrow, or notifications are off." }
                    }) { Text("20:00 now") }
                }
                debugNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun Problem(text: String, action: @Composable () -> Unit) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    action()
}

private fun isBackgroundRestricted(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        context.getSystemService(ActivityManager::class.java).isBackgroundRestricted

private fun appNotificationSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

private fun Context.open(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
