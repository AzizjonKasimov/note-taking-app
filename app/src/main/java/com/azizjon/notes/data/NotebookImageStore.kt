package com.azizjon.notes.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class NotebookMediaPayload(
    val notebookId: Long,
    val source: ByteArray?,
    val crop: ByteArray?,
)

data class PreparedNotebookMediaRestore(
    val notebooks: List<Notebook>,
    val stagingDirectory: File,
    val fallbackCount: Int,
    val missingSourceCount: Int,
)

/** Owns the private, optimized source and square crop files used by custom notebook markers. */
class NotebookImageStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, DIRECTORY)
    private val cache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    suspend fun decodePickedPhoto(uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val orientation = resolver.openInputStream(uri)?.use { input ->
            runCatching {
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected image could not be read" }
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > SOURCE_MAX_EDGE * 2) {
            sample *= 2
        }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("The selected image could not be decoded")
        val oriented = applyOrientation(decoded, orientation)
        scaleDown(oriented, SOURCE_MAX_EDGE)
    }

    suspend fun saveCustomPhoto(
        notebookId: Long,
        source: Bitmap,
        crop: NormalizedCrop,
    ) = withContext(Dispatchers.IO) {
        val optimized = scaleDown(source, SOURCE_MAX_EDGE)
        val cropped = renderCrop(optimized, crop, CROP_EDGE)
        val dir = notebookDirectory(notebookId).apply { mkdirs() }
        writeWebpAtomically(sourceFile(notebookId), optimized, SOURCE_QUALITY)
        writeWebpAtomically(cropFile(notebookId), cropped, CROP_QUALITY)
        invalidate(notebookId)
    }

    suspend fun loadCrop(notebookId: Long): Bitmap? = withContext(Dispatchers.IO) {
        loadBitmapCached(cropFile(notebookId), "crop-$notebookId")
    }

    suspend fun loadSource(notebookId: Long): Bitmap? = withContext(Dispatchers.IO) {
        loadBitmapCached(sourceFile(notebookId), "source-$notebookId")
    }

    suspend fun readPayload(notebookId: Long): NotebookMediaPayload = withContext(Dispatchers.IO) {
        NotebookMediaPayload(
            notebookId = notebookId,
            source = sourceFile(notebookId).takeIf(File::isFile)?.readBytes(),
            crop = cropFile(notebookId).takeIf(File::isFile)?.readBytes(),
        )
    }

    suspend fun delete(notebookId: Long) = withContext(Dispatchers.IO) {
        notebookDirectory(notebookId).deleteRecursively()
        invalidate(notebookId)
    }

    suspend fun prepareRestore(
        notebooks: List<Notebook>,
        payloads: List<NotebookMediaPayload>,
    ): PreparedNotebookMediaRestore = withContext(Dispatchers.IO) {
        val staging = File(appContext.cacheDir, "notebook-marker-restore-${System.nanoTime()}")
        staging.mkdirs()
        val byId = payloads.associateBy { it.notebookId }
        var fallbacks = 0
        var missingSources = 0

        val safeNotebooks = notebooks.map { notebook ->
            if (notebook.appearance().type != NotebookMarkerType.CUSTOM_PHOTO) return@map notebook
            val payload = byId[notebook.id]
            val sourceBitmap = payload?.source.decodeBitmapOrNull()
            var cropBitmap = payload?.crop.decodeBitmapOrNull()
            if (cropBitmap == null && sourceBitmap != null) {
                cropBitmap = renderCrop(sourceBitmap, notebook.appearance().crop, CROP_EDGE)
            }
            if (cropBitmap == null) {
                fallbacks++
                return@map notebook.withAppearance(NotebookAppearance())
            }

            val target = File(staging, notebook.id.toString()).apply { mkdirs() }
            payload?.source?.takeIf { sourceBitmap != null }?.let { File(target, SOURCE_FILE).writeBytes(it) }
            if (sourceBitmap == null) missingSources++
            val cropBytes = payload?.crop
            if (cropBytes.decodeBitmapOrNull() != null) {
                File(target, CROP_FILE).writeBytes(cropBytes!!)
            } else {
                writeWebpAtomically(File(target, CROP_FILE), cropBitmap, CROP_QUALITY)
            }
            notebook
        }

        PreparedNotebookMediaRestore(safeNotebooks, staging, fallbacks, missingSources)
    }

    suspend fun commitRestore(prepared: PreparedNotebookMediaRestore) = withContext(Dispatchers.IO) {
        val old = File(appContext.filesDir, "$DIRECTORY-old")
        old.deleteRecursively()
        if (root.exists() && !root.renameTo(old)) error("Could not stage existing notebook images")
        if (!prepared.stagingDirectory.renameTo(root)) {
            old.renameTo(root)
            error("Could not install restored notebook images")
        }
        old.deleteRecursively()
        cache.evictAll()
    }

    fun discardRestore(prepared: PreparedNotebookMediaRestore) {
        prepared.stagingDirectory.deleteRecursively()
    }

    fun invalidate(notebookId: Long) {
        cache.remove("crop-$notebookId")
        cache.remove("source-$notebookId")
    }

    private fun loadBitmapCached(file: File, key: String): Bitmap? {
        cache.get(key)?.let { return it }
        val bitmap = file.takeIf(File::isFile)?.let { BitmapFactory.decodeFile(it.path) } ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    private fun notebookDirectory(id: Long): File = File(root, id.toString())
    private fun sourceFile(id: Long): File = File(notebookDirectory(id), SOURCE_FILE)
    private fun cropFile(id: Long): File = File(notebookDirectory(id), CROP_FILE)

    companion object {
        private const val DIRECTORY = "notebook-markers"
        private const val SOURCE_FILE = "source.webp"
        private const val CROP_FILE = "crop.webp"
        const val SOURCE_MAX_EDGE = 2048
        const val CROP_EDGE = 256
        private const val SOURCE_QUALITY = 88
        private const val CROP_QUALITY = 85

        fun defaultCrop(bitmap: Bitmap): NormalizedCrop {
            val shortEdge = min(bitmap.width, bitmap.height).toFloat()
            return NormalizedCrop(
                left = ((bitmap.width - shortEdge) / 2f) / bitmap.width,
                top = ((bitmap.height - shortEdge) / 2f) / bitmap.height,
                size = 1f,
            )
        }

        fun renderCrop(bitmap: Bitmap, crop: NormalizedCrop, edge: Int = CROP_EDGE): Bitmap {
            val safe = constrainCrop(bitmap, crop)
            val sourceSize = (safe.size * min(bitmap.width, bitmap.height)).roundToInt().coerceAtLeast(1)
            val left = (safe.left * bitmap.width).roundToInt().coerceIn(0, bitmap.width - sourceSize)
            val top = (safe.top * bitmap.height).roundToInt().coerceIn(0, bitmap.height - sourceSize)
            val output = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
            Canvas(output).drawBitmap(
                bitmap,
                Rect(left, top, left + sourceSize, top + sourceSize),
                Rect(0, 0, edge, edge),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
            return output
        }

        fun constrainCrop(bitmap: Bitmap, crop: NormalizedCrop): NormalizedCrop {
            val safeSize = crop.size.coerceIn(0.08f, 1f)
            val sourceSize = safeSize * min(bitmap.width, bitmap.height)
            val maxLeft = ((bitmap.width - sourceSize) / bitmap.width).coerceAtLeast(0f)
            val maxTop = ((bitmap.height - sourceSize) / bitmap.height).coerceAtLeast(0f)
            return NormalizedCrop(
                left = crop.left.coerceIn(0f, maxLeft),
                top = crop.top.coerceIn(0f, maxTop),
                size = safeSize,
            )
        }

        private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
            val largest = max(bitmap.width, bitmap.height)
            if (largest <= maxEdge) return bitmap
            val ratio = maxEdge.toFloat() / largest
            return Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
                (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
                true,
            )
        }

        private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.setRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.setRotate(-90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
                else -> return bitmap
            }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        private fun writeWebpAtomically(file: File, bitmap: Bitmap, quality: Int) {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(temporary).use { output ->
                val format = if (Build.VERSION.SDK_INT >= 30) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                check(bitmap.compress(format, quality, output)) { "Could not encode notebook image" }
            }
            if (file.exists() && !file.delete()) error("Could not replace notebook image")
            if (!temporary.renameTo(file)) error("Could not finish notebook image")
        }

        private fun ByteArray?.decodeBitmapOrNull(): Bitmap? =
            this?.let { bytes -> runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() }
    }
}
