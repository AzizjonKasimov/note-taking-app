package com.azizjon.notes.people.checkin

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
 * Takes the check-in's answers from Network App, once ([CheckinHandover]). It shows nothing but a
 * short message, and answers Network App with how many it took, or why it took none.
 */
class CheckinHandoverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val caller = callingPackage
        val address = intent.data
        val trusted = intent.action == CheckinHandover.ACTION &&
            caller == CheckinHandover.NETWORK_APP_PACKAGE &&
            CheckinHandover.signedBy(packageManager, caller, CheckinHandover.networkAppCerts)
        if (!trusted || address == null) {
            finishWith(error = "Notes takes check-in answers only from your own Network App.")
            return
        }
        lifecycleScope.launch {
            val taken = runCatching {
                val text = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(address)?.use { input ->
                        // Read in pieces with a limit: InputStream.readNBytes needs Android 13.
                        val out = ByteArrayOutputStream()
                        val piece = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(piece)
                            if (n < 0) break
                            out.write(piece, 0, n)
                            require(out.size() <= MAX_BYTES) { "The hand-over file is too large." }
                        }
                        out.toByteArray().toString(Charsets.UTF_8)
                    }
                } ?: throw IllegalArgumentException("The hand-over file could not be opened.")
                val rows = CheckinHandover.decode(text)
                (application as NotesApplication).people.checkins.takeHandover(rows)
                rows.size
            }
            taken.onSuccess { count ->
                Toast.makeText(this@CheckinHandoverActivity, "Notes took Network App's $count check-in answers", Toast.LENGTH_LONG).show()
                setResult(RESULT_OK, Intent().putExtra(CheckinHandover.EXTRA_COUNT, count))
                finish()
            }.onFailure { error ->
                finishWith(error = error.message ?: "The check-in answers could not be taken.")
            }
        }
    }

    private fun finishWith(error: String) {
        setResult(RESULT_CANCELED, Intent().putExtra(CheckinHandover.EXTRA_ERROR, error))
        finish()
    }

    private companion object {
        /** Far more than the answers of any phone's contacts and chats. */
        const val MAX_BYTES = 8 * 1024 * 1024
    }
}
