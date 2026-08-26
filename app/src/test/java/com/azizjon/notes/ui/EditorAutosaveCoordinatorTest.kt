package com.azizjon.notes.ui

import com.azizjon.notes.data.EditorSnapshot
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorAutosaveCoordinatorTest {
    private val baseline = EditorSnapshot(7L, "", "", 1L)

    @Test
    fun idleAutosave_coalescesHundredsOfChangesIntoLatestRevision() = runBlocking {
        val writes = Collections.synchronizedList(mutableListOf<EditorSnapshot>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = EditorAutosaveCoordinator(
            scope = scope,
            writer = { writes += it },
            onSuccessfulWrite = {},
            idleDelayMillis = 30L,
            maximumDelayMillis = 1_000L,
        )
        try {
            coordinator.begin(baseline)
            repeat(300) { revision ->
                coordinator.submit(baseline.copy(title = "revision-$revision"))
            }

            withTimeout(2_000L) {
                while (writes.isEmpty()) delay(10L)
            }
            assertEquals("revision-299", writes.last().title)
            assertEquals(EditorSaveStatus.SAVED, coordinator.status.value)
        } finally {
            coordinator.endSession()
            scope.cancel()
        }
    }

    @Test
    fun maximumTimer_savesDuringContinuousTyping() = runBlocking {
        val writes = Collections.synchronizedList(mutableListOf<EditorSnapshot>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = EditorAutosaveCoordinator(
            scope = scope,
            writer = { writes += it },
            onSuccessfulWrite = {},
            idleDelayMillis = 1_000L,
            maximumDelayMillis = 70L,
        )
        try {
            coordinator.begin(baseline)
            repeat(10) { revision ->
                coordinator.submit(baseline.copy(content = "typing-$revision"))
                delay(20L)
            }
            assertTrue("at least one write happens before typing becomes idle", writes.isNotEmpty())
            coordinator.flush().getOrThrow()
            assertEquals("typing-9", writes.last().content)
        } finally {
            coordinator.endSession()
            scope.cancel()
        }
    }

    @Test
    fun serializedFlush_neverLetsAnOlderWriteLandAfterANewerRevision() = runBlocking {
        val writes = Collections.synchronizedList(mutableListOf<String>())
        val firstWriteStarted = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = EditorAutosaveCoordinator(
            scope = scope,
            writer = { snapshot ->
                writes += snapshot.title
                if (snapshot.title == "older") {
                    firstWriteStarted.complete(Unit)
                    releaseFirstWrite.await()
                }
            },
            onSuccessfulWrite = {},
            idleDelayMillis = 10_000L,
            maximumDelayMillis = 10_000L,
        )
        try {
            coordinator.begin(baseline)
            coordinator.submit(baseline.copy(title = "older"))
            val flush = async(Dispatchers.Default) { coordinator.flush() }
            firstWriteStarted.await()
            coordinator.submit(baseline.copy(title = "newest"))
            releaseFirstWrite.complete(Unit)

            flush.await().getOrThrow()
            assertEquals(listOf("older", "newest"), writes)
            assertEquals(EditorSaveStatus.SAVED, coordinator.status.value)
        } finally {
            coordinator.endSession()
            scope.cancel()
        }
    }

    @Test
    fun failedWrite_staysDirtyAndRetryPersistsIt() = runBlocking {
        val shouldFail = AtomicBoolean(true)
        val writes = Collections.synchronizedList(mutableListOf<EditorSnapshot>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = EditorAutosaveCoordinator(
            scope = scope,
            writer = {
                if (shouldFail.getAndSet(false)) error("disk full")
                writes += it
            },
            onSuccessfulWrite = {},
            idleDelayMillis = 10_000L,
            maximumDelayMillis = 10_000L,
        )
        try {
            coordinator.begin(baseline)
            coordinator.submit(baseline.copy(title = "recover me"))
            assertTrue(coordinator.flush().isFailure)
            assertEquals(EditorSaveStatus.ERROR, coordinator.status.value)

            coordinator.retry().getOrThrow()
            assertEquals("recover me", writes.single().title)
            assertEquals(EditorSaveStatus.SAVED, coordinator.status.value)
        } finally {
            coordinator.endSession()
            scope.cancel()
        }
    }
}
