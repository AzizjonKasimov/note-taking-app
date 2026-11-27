package com.azizjon.notes

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import com.azizjon.notes.people.checkin.EveningCheckin
import com.azizjon.notes.people.ui.AppSection
import com.azizjon.notes.people.ui.NetworkViewModel
import com.azizjon.notes.reminders.ReminderNotifications
import com.azizjon.notes.ui.NotesApp
import com.azizjon.notes.ui.theme.AppThemeMode
import com.azizjon.notes.ui.theme.NotesTheme

class MainActivity : ComponentActivity() {
    /** People's screens, kept by the activity so the assistant's conversation outlasts a switch of tab. */
    private val people: NetworkViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        val app = application as NotesApplication
        app.life.onForeground()
        app.reminders.onAppOpened()
        // Coming back from the PC is when its drafts for People are waiting.
        people.checkInbox()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A reminder tapped while the app was open: show Today. (Opened from scratch, the app starts there.)
        if (intent.action == ReminderNotifications.ACTION_OPEN_TODAY) (application as NotesApplication).reminders.requestOpenToday()
        // The activity is single-task, so a share or People's notification arrives here once it runs.
        handlePeopleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        // The editor has saved by now (it flushes on stop); send the edits up, or as soon as online.
        (application as NotesApplication).life.syncSoon(delaySeconds = 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A recreated activity already handled the intent that started it.
        if (savedInstanceState == null) handlePeopleIntent(intent)
        val settingsPrefs = getSharedPreferences(AppThemeMode.PREFS_NAME, Context.MODE_PRIVATE)
        setContent {
            var themeMode by remember {
                mutableStateOf(AppThemeMode.fromPref(settingsPrefs.getString(AppThemeMode.PREF_KEY, null)))
            }
            val darkTheme = themeMode.isDark(isSystemInDarkTheme())
            SideEffect {
                val systemBarStyle = if (darkTheme) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(
                    statusBarStyle = systemBarStyle,
                    navigationBarStyle = systemBarStyle,
                )
            }

            NotesTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    NotesApp(
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            themeMode = mode
                            settingsPrefs.edit().putString(AppThemeMode.PREF_KEY, mode.prefValue).apply()
                        },
                        people = people,
                    )
                }
            }
        }
    }

    /** Photos and text shared from other apps go to the people assistant; the check-in opens its section. */
    private fun handlePeopleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> people.receiveShare(
                text = intent.getStringExtra(Intent.EXTRA_TEXT),
                photos = listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                    .takeIf { intent.type?.startsWith("image/") == true }
                    .orEmpty(),
            )
            Intent.ACTION_SEND_MULTIPLE -> people.receiveShare(
                text = intent.getStringExtra(Intent.EXTRA_TEXT),
                photos = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty(),
            )
        }
        when (intent.getStringExtra(EveningCheckin.EXTRA_OPEN)) {
            EveningCheckin.OPEN_CHECKIN -> people.openSection(AppSection.CHECKIN)
            EveningCheckin.OPEN_ASSISTANT -> people.openSection(AppSection.CHAT)
        }
    }
}
