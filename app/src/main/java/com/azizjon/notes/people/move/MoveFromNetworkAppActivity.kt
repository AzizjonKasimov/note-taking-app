package com.azizjon.notes.people.move

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.azizjon.notes.NotesApplication
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Takes Network App's people and check-in answers, once ([MoveFromNetworkApp]), into a People that
 * is still empty, then saves them to the notes repo. It shows nothing but a short message, and
 * answers Network App with how many people came, or why none did.
 */
class MoveFromNetworkAppActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val caller = callingPackage
        val address = intent.data
        val trusted = intent.action == MoveFromNetworkApp.ACTION &&
            caller == MoveFromNetworkApp.NETWORK_APP_PACKAGE &&
            MoveFromNetworkApp.signedBy(packageManager, caller, MoveFromNetworkApp.networkAppCerts)
        if (!trusted || address == null) {
            finishWith(error = "Notes takes people only from your own Network App.")
            return
        }
        val people = (application as NotesApplication).people
        lifecycleScope.launch {
            val moved = runCatching {
                val text = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(address)?.use { input ->
                        // Read in pieces with a limit: InputStream.readNBytes needs Android 13.
                        val out = ByteArrayOutputStream()
                        val piece = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(piece)
                            if (n < 0) break
                            out.write(piece, 0, n)
                            require(out.size() <= MAX_BYTES) { "The move's file is too large." }
                        }
                        out.toByteArray().toString(Charsets.UTF_8)
                    }
                } ?: throw IllegalArgumentException("The move's file could not be opened.")
                val move = MoveFromNetworkApp.decode(text)
                // Never over people already here: they may hold changes made in Notes.
                require(people.repository.snapshot().people.isEmpty()) { "Its People tab already has people, and a move only fills an empty one." }
                people.repository.replaceAll(move.payload.snapshot, move.payload.feedback)
                people.checkins.takeHandover(move.checkins)
                people.saveState.markMovedFromNetworkApp(move.payload.snapshot.people.size)
                move.payload.snapshot.people.size
            }
            moved.onSuccess { count ->
                // Saved in the app's own scope, so it finishes after this screen has closed. If the
                // notes repo is not on the phone yet, the save stays due until it is.
                people.appScope.launch {
                    runCatching {
                        if (people.peopleInLife.owns) {
                            people.peopleInLife.save()
                            people.saveState.markSaved()
                        }
                    }
                }
                Toast.makeText(this@MoveFromNetworkAppActivity, "$count people moved from Network App into Notes", Toast.LENGTH_LONG).show()
                setResult(RESULT_OK, Intent().putExtra(MoveFromNetworkApp.EXTRA_PEOPLE, count))
                finish()
            }.onFailure { error ->
                finishWith(error = error.message ?: "Nothing moved.")
            }
        }
    }

    private fun finishWith(error: String) {
        setResult(RESULT_CANCELED, Intent().putExtra(MoveFromNetworkApp.EXTRA_ERROR, error))
        finish()
    }

    private companion object {
        /** Far more than a large network: today's is about a quarter of a megabyte. */
        const val MAX_BYTES = 32 * 1024 * 1024
    }
}
