package com.azizjon.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.azizjon.notes.backup.BackupData
import com.azizjon.notes.backup.GitHubBackupConfig
import com.azizjon.notes.backup.GitHubBackupSettings
import com.azizjon.notes.backup.GitHubContent
import com.azizjon.notes.backup.GitHubContentsClient
import com.azizjon.notes.backup.GitHubSqlBackupManager
import com.azizjon.notes.backup.SqlBackupCodec
import com.azizjon.notes.data.Notebook
import com.azizjon.notes.data.NotebookAppearance
import com.azizjon.notes.data.NotebookImageStore
import com.azizjon.notes.data.NotebookMarkerType
import com.azizjon.notes.data.NotebookMediaPayload
import com.azizjon.notes.data.NormalizedCrop
import com.azizjon.notes.data.appearance
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotebookMediaBackupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = GitHubBackupConfig(
        owner = "owner",
        repo = "repo",
        branch = "main",
        path = "backup/notes.sql",
        token = "token",
    )

    @Test
    fun backup_uploadsImagesBeforeSql_thenCleansOldImages() = runBlocking {
        val store = NotebookImageStore(context)
        val custom = Notebook(
            id = 2,
            name = "Photos",
            markerType = NotebookMarkerType.CUSTOM_PHOTO.name,
        )
        store.saveCustomPhoto(2, sampleBitmap(), NotebookImageStore.defaultCrop(sampleBitmap()))

        val previous = Notebook(
            id = 3,
            name = "Old",
            markerType = NotebookMarkerType.CUSTOM_PHOTO.name,
        )
        val fake = FakeGitHubContentsClient().apply {
            seed("backup/notes.sql", SqlBackupCodec.serialize(BackupData(listOf(previous), emptyList())).toByteArray())
            seed("backup/notebook-images/3/source.webp", byteArrayOf(1))
            seed("backup/notebook-images/3/crop.webp", byteArrayOf(2))
        }
        val manager = GitHubSqlBackupManager(context, GitHubBackupSettings(context), store, fake)

        val result = manager.backup(config, BackupData(listOf(custom), emptyList()))

        val sourcePut = fake.events.indexOf("PUT backup/notebook-images/2/source.webp")
        val cropPut = fake.events.indexOf("PUT backup/notebook-images/2/crop.webp")
        val sqlPut = fake.events.indexOf("PUT backup/notes.sql")
        val cleanup = fake.events.indexOf("DELETE backup/notebook-images/3/source.webp")
        assertTrue(sourcePut in 0 until sqlPut)
        assertTrue(cropPut in 0 until sqlPut)
        assertTrue(cleanup > sqlPut)
        assertEquals(2, result.imagesUploaded)

        val second = manager.backup(config, BackupData(listOf(custom), emptyList()))
        assertEquals(0, second.imagesUploaded)
        store.delete(2)
    }

    @Test
    fun imageUploadFailure_doesNotReplaceSql() = runBlocking {
        val store = NotebookImageStore(context)
        val bitmap = sampleBitmap()
        store.saveCustomPhoto(4, bitmap, NotebookImageStore.defaultCrop(bitmap))
        val custom = Notebook(
            id = 4,
            name = "Blocked",
            markerType = NotebookMarkerType.CUSTOM_PHOTO.name,
        )
        val fake = FakeGitHubContentsClient(failPutPath = "backup/notebook-images/4/crop.webp")
        val manager = GitHubSqlBackupManager(context, GitHubBackupSettings(context), store, fake)

        runCatching { manager.backup(config, BackupData(listOf(custom), emptyList())) }

        assertFalse(fake.events.contains("PUT backup/notes.sql"))
        store.delete(4)
    }

    @Test
    fun prepareRestore_regeneratesCropOrFallsBackToAuto() = runBlocking {
        val store = NotebookImageStore(context)
        val bitmap = sampleBitmap()
        store.saveCustomPhoto(5, bitmap, NormalizedCrop(0f, 0f, 0.5f))
        val source = store.readPayload(5).source
        store.delete(5)
        val custom = Notebook(
            id = 5,
            name = "Recover",
            markerType = NotebookMarkerType.CUSTOM_PHOTO.name,
            cropSize = 0.5f,
        )

        val regenerated = store.prepareRestore(
            listOf(custom),
            listOf(NotebookMediaPayload(5, source = source, crop = null)),
        )
        assertEquals(0, regenerated.fallbackCount)
        assertNotNull(BitmapFactoryCompat.decode(FileCompat.crop(regenerated, 5)))
        store.discardRestore(regenerated)

        val fallback = store.prepareRestore(
            listOf(custom),
            listOf(NotebookMediaPayload(5, source = null, crop = byteArrayOf(9, 8, 7))),
        )
        assertEquals(1, fallback.fallbackCount)
        assertEquals(NotebookMarkerType.AUTO, fallback.notebooks.single().appearance().type)
        store.discardRestore(fallback)
    }

    private fun sampleBitmap(): Bitmap = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.rgb(35, 105, 180))
    }
}

private class FakeGitHubContentsClient(
    private val failPutPath: String? = null,
) : GitHubContentsClient {
    private val files = linkedMapOf<String, GitHubContent>()
    private var sequence = 0
    val events = mutableListOf<String>()

    fun seed(path: String, bytes: ByteArray) {
        files[path] = GitHubContent("seed-${sequence++}", bytes)
    }

    override fun get(config: GitHubBackupConfig, path: String): GitHubContent? {
        events += "GET $path"
        return files[path]
    }

    override fun put(
        config: GitHubBackupConfig,
        path: String,
        bytes: ByteArray,
        sha: String?,
        message: String,
    ) {
        events += "PUT $path"
        if (path == failPutPath) error("simulated upload failure")
        files[path] = GitHubContent("sha-${sequence++}", bytes)
    }

    override fun delete(
        config: GitHubBackupConfig,
        path: String,
        sha: String,
        message: String,
    ) {
        events += "DELETE $path"
        files.remove(path)
    }
}

private object FileCompat {
    fun crop(prepared: com.azizjon.notes.data.PreparedNotebookMediaRestore, id: Long) =
        java.io.File(java.io.File(prepared.stagingDirectory, id.toString()), "crop.webp")
}

private object BitmapFactoryCompat {
    fun decode(file: java.io.File): Bitmap? = android.graphics.BitmapFactory.decodeFile(file.path)
}
