package com.azizjon.notes.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

object Routes {
    const val LIST = "list"
    const val EDIT = "edit"
    const val ARG_ID = "noteId"
    fun edit(id: Long) = "$EDIT/$id"
}

/** App entry composable: owns the nav graph and the shared [NotesViewModel]. */
@Composable
fun NotesApp() {
    val navController = rememberNavController()
    val viewModel: NotesViewModel = viewModel(factory = NotesViewModel.Factory)

    NavHost(navController = navController, startDestination = Routes.LIST) {
        composable(Routes.LIST) {
            NotesListScreen(
                viewModel = viewModel,
                onAddNote = { navController.navigate(Routes.edit(0)) },
                onOpenNote = { id -> navController.navigate(Routes.edit(id)) },
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
                onDone = { navController.popBackStack() },
            )
        }
    }
}
