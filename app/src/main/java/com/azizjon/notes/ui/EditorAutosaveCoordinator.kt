package com.azizjon.notes.ui

import com.azizjon.notes.data.EditorSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class EditorSaveStatus {
    SAVING,
    SAVED,
    ERROR,
}

/** Serializes editor writes and coalesces rapid revisions without ever writing an older revision last. */
class EditorAutosaveCoordinator(
    private val scope: CoroutineScope,
    private val writer: suspend (EditorSnapshot) -> Unit,
    private val onSuccessfulWrite: () -> Unit,
    private val idleDelayMillis: Long = 350L,
    private val maximumDelayMillis: Long = 2_000L,
) {
    private data class Revision(val number: Long, val snapshot: EditorSnapshot)

    private val stateLock = Any()
    private val writeMutex = Mutex()
    private val _status = MutableStateFlow(EditorSaveStatus.SAVED)
    val status: StateFlow<EditorSaveStatus> = _status.asStateFlow()

    private var latest: Revision? = null
    private var savedRevision = 0L
    private var nextRevision = 0L
    private var idleJob: Job? = null
    private var maximumJob: Job? = null

    /** Establishes a loaded database value as the saved baseline without writing it again. */
    fun begin(snapshot: EditorSnapshot) {
        synchronized(stateLock) {
            idleJob?.cancel()
            maximumJob?.cancel()
            idleJob = null
            maximumJob = null
            nextRevision = 0L
            savedRevision = 0L
            latest = Revision(0L, snapshot)
            _status.value = EditorSaveStatus.SAVED
        }
    }

    fun submit(snapshot: EditorSnapshot) {
        synchronized(stateLock) {
            if (latest?.snapshot == snapshot) return
            nextRevision += 1L
            latest = Revision(nextRevision, snapshot)
            _status.value = EditorSaveStatus.SAVING

            idleJob?.cancel()
            idleJob = scope.launch {
                delay(idleDelayMillis)
                synchronized(stateLock) { idleJob = null }
                persistLatest()
            }

            if (maximumJob == null) {
                maximumJob = scope.launch {
                    delay(maximumDelayMillis)
                    synchronized(stateLock) { maximumJob = null }
                    persistLatest()
                }
            }
        }
    }

    /** Writes every revision that became current before this call completes. */
    suspend fun flush(): Result<Unit> {
        cancelTimers()
        return persistLatest()
    }

    suspend fun retry(): Result<Unit> = flush()

    fun reportError() {
        _status.value = EditorSaveStatus.ERROR
    }

    fun latestSnapshot(): EditorSnapshot? = synchronized(stateLock) { latest?.snapshot }

    /** Stops pending work and waits for any Room call already in progress before dropping state. */
    suspend fun endSession() {
        cancelTimers()
        writeMutex.withLock {
            synchronized(stateLock) {
                latest = null
                savedRevision = 0L
                nextRevision = 0L
                _status.value = EditorSaveStatus.SAVED
            }
        }
    }

    private fun cancelTimers() {
        synchronized(stateLock) {
            idleJob?.cancel()
            maximumJob?.cancel()
            idleJob = null
            maximumJob = null
        }
    }

    private suspend fun persistLatest(): Result<Unit> = writeMutex.withLock {
        while (true) {
            val target = synchronized(stateLock) {
                latest?.takeIf { it.number > savedRevision }
            } ?: run {
                _status.value = EditorSaveStatus.SAVED
                return@withLock Result.success(Unit)
            }

            try {
                writer(target.snapshot)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _status.value = EditorSaveStatus.ERROR
                return@withLock Result.failure(error)
            }

            synchronized(stateLock) {
                savedRevision = maxOf(savedRevision, target.number)
            }
            onSuccessfulWrite()

            val caughtUp = synchronized(stateLock) {
                latest?.number == savedRevision
            }
            if (caughtUp) {
                _status.value = EditorSaveStatus.SAVED
                return@withLock Result.success(Unit)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        Result.success(Unit)
    }
}
