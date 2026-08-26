package com.azizjon.notes.ui

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
import com.azizjon.notes.update.UpdatePrompt
import com.azizjon.notes.ui.theme.AppThemeMode
import kotlinx.coroutines.launch

object Routes {
    const val LIST = "list"
    const val EDIT = "edit"
    const val BACKUP = "backup"
    const val TRASH = "trash"
    const val ARG_ID = "noteId"
    fun edit(id: Long) = "$EDIT/$id"
}

/** App entry composable: owns the nav graph and the shared [NotesViewModel]. */
@Composable
fun NotesApp(
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
) {
    val viewModel: NotesViewModel = viewModel(factory = NotesViewModel.Factory)
    val startupState by viewModel.startupState.collectAsStateWithLifecycle()

    when (val state = startupState) {
        AppStartupState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        AppStartupState.NoteList -> NotesNavGraph(viewModel, Routes.LIST, themeMode, onThemeModeChange)
        is AppStartupState.ResumeEditor -> NotesNavGraph(
            viewModel,
            Routes.edit(state.noteId),
            themeMode,
            onThemeModeChange,
        )
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
    var creatingDraft by remember { mutableStateOf(false) }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.LIST) {
            NotesListScreen(
                viewModel = viewModel,
                onAddNote = {
                    if (!creatingDraft) {
                        creatingDraft = true
                        scope.launch {
                            try {
                                runCatching { viewModel.createDraft() }
                                    .onSuccess { id -> navController.navigate(Routes.edit(id)) }
                            } finally {
                                creatingDraft = false
                            }
                        }
                    }
                },
                onOpenNote = { id -> navController.navigate(Routes.edit(id)) },
                onOpenTrash = { navController.navigate(Routes.TRASH) },
                onOpenBackup = { navController.navigate(Routes.BACKUP) },
            )
        }
        composable(
            route = "${Routes.EDIT}/{${Routes.ARG_ID}}",
            arguments = listOf(navArgument(Routes.ARG_ID) { type = NavType.LongType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong(Routes.ARG_ID) ?: 0L
            NoteEditScreen(
                viewModel = viewModel,
                noteId = id,
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
        composable(Routes.BACKUP) {
            BackupScreen(
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
