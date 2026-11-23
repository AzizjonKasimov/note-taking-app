package com.azizjon.notes.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.azizjon.notes.life.Connection
import com.azizjon.notes.ui.theme.AppThemeMode
import com.azizjon.notes.update.UpdatePrompt
import kotlinx.coroutines.launch

object Routes {
    const val LIST = "list"
    const val EDIT = "edit"
    const val SETTINGS = "settings"
    const val TRASH = "trash"
    const val ARG_PATH = "path"
    const val ARG_NEW = "new"
    const val ARG_VOICE = "voice"
    fun edit(path: String, isNew: Boolean = false, voice: Boolean = false) =
        "$EDIT?$ARG_PATH=${Uri.encode(path)}&$ARG_NEW=$isNew&$ARG_VOICE=$voice"
}

/** App entry composable: the connect screen until the repo is on the phone, then the notes. */
@Composable
fun NotesApp(
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
) {
    val viewModel: NotesViewModel = viewModel(factory = NotesViewModel.Factory)
    val startupState by viewModel.startupState.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()

    when {
        connection !is Connection.Ready -> ConnectScreen(viewModel, connection)
        startupState is AppStartupState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> {
            val start = (startupState as? AppStartupState.ResumeEditor)?.let { Routes.edit(it.path, it.isNew) }
            NotesNavGraph(viewModel, start ?: Routes.LIST, themeMode, onThemeModeChange)
        }
    }

    // Checks for a newer release on launch and offers a one-tap in-app update.
    UpdatePrompt()
}

@Composable
private fun NotesNavGraph(
    viewModel: NotesViewModel,
    startDestination: String,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    var creatingNote by remember { mutableStateOf(false) }

    fun addNote(voice: Boolean) {
        if (creatingNote) return
        creatingNote = true
        scope.launch {
            try {
                runCatching { viewModel.startNewNote() }
                    .onSuccess { path -> navController.navigate(Routes.edit(path, isNew = true, voice = voice)) }
            } finally {
                creatingNote = false
            }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.LIST) {
            NotesListScreen(
                viewModel = viewModel,
                onAddNote = { addNote(voice = false) },
                onAddVoiceNote = { addNote(voice = true) },
                onOpenNote = { path -> navController.navigate(Routes.edit(path)) },
                onOpenTrash = { navController.navigate(Routes.TRASH) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = "${Routes.EDIT}?${Routes.ARG_PATH}={${Routes.ARG_PATH}}" +
                "&${Routes.ARG_NEW}={${Routes.ARG_NEW}}&${Routes.ARG_VOICE}={${Routes.ARG_VOICE}}",
            arguments = listOf(
                navArgument(Routes.ARG_PATH) {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument(Routes.ARG_NEW) {
                    type = NavType.BoolType
                    defaultValue = false
                },
                navArgument(Routes.ARG_VOICE) {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { backStackEntry ->
            val args = backStackEntry.arguments
            NoteEditScreen(
                viewModel = viewModel,
                path = args?.getString(Routes.ARG_PATH).orEmpty(),
                isNew = args?.getBoolean(Routes.ARG_NEW) ?: false,
                startVoice = args?.getBoolean(Routes.ARG_VOICE) ?: false,
                onDone = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Routes.LIST) {
                            popUpTo(navController.graph.startDestinationId) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.TRASH) {
            TrashScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
