package com.azizjon.notes.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.azizjon.notes.data.Notebook
import com.azizjon.notes.data.NotebookAppearance
import com.azizjon.notes.data.NotebookImageStore
import com.azizjon.notes.data.NotebookMarkerType
import com.azizjon.notes.data.NormalizedCrop
import com.azizjon.notes.data.appearance
import com.azizjon.notes.data.firstGrapheme
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

private val QUICK_EMOJI = listOf(
    "📘", "📚", "✍️", "💡", "✅", "🎯", "💼", "💻",
    "🏠", "🌱", "🌍", "✈️", "🎨", "🎵", "📷", "🧠",
    "❤️", "⭐", "🔥", "☕", "🍳", "🏋️", "💰", "🔬",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NotebookAppearanceSheet(
    notebook: Notebook,
    viewModel: NotesViewModel,
    onDismiss: () -> Unit,
) {
    val current = notebook.appearance()
    var typeName by rememberSaveable(notebook.id) { mutableStateOf(current.type.name) }
    var color by rememberSaveable(notebook.id) { mutableIntStateOf(current.color) }
    var value by rememberSaveable(notebook.id) { mutableStateOf(current.value) }
    var error by rememberSaveable(notebook.id) { mutableStateOf<String?>(null) }
    var sourceBitmap by remember(notebook.id) { mutableStateOf<Bitmap?>(null) }
    var sourceMode by rememberSaveable(notebook.id) { mutableStateOf<String?>(null) }
    var pendingUri by rememberSaveable(notebook.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val type = NotebookMarkerType.fromStored(typeName)

    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) {
            pendingUri = uri.toString()
            sourceMode = "picked"
            error = null
        }
    }

    LaunchedEffect(pendingUri, sourceMode) {
        val uriText = pendingUri ?: return@LaunchedEffect
        runCatching { viewModel.decodePickedPhoto(Uri.parse(uriText)) }
            .onSuccess {
                sourceBitmap = it
                pendingUri = null
            }
            .onFailure {
                error = it.message ?: "Could not open that photo"
                pendingUri = null
                sourceMode = null
            }
    }

    LaunchedEffect(sourceMode) {
        if (sourceMode == "existing" && sourceBitmap == null) {
            val source = viewModel.loadCustomPhotoSource(notebook.id)
            if (source != null) {
                sourceBitmap = source
            } else {
                error = "The editable source is missing. Choose the photo again to re-crop it."
                sourceMode = null
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NotebookMarker(notebook.withPreview(type, color, value), 48.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text("Notebook appearance", style = MaterialTheme.typography.titleLarge)
                    Text(
                        notebook.name.ifBlank { "Untitled" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MarkerChoice("Auto", NotebookMarkerType.AUTO, type) { typeName = it.name }
                MarkerChoice("Folder", NotebookMarkerType.FOLDER, type) { typeName = it.name }
                MarkerChoice("Initial", NotebookMarkerType.INITIAL, type) { typeName = it.name }
                MarkerChoice("Emoji", NotebookMarkerType.EMOJI, type) { typeName = it.name }
                MarkerChoice("Photos", NotebookMarkerType.PRESET_PHOTO, type) { typeName = it.name }
                if (current.type == NotebookMarkerType.CUSTOM_PHOTO) {
                    MarkerChoice("Gallery", NotebookMarkerType.CUSTOM_PHOTO, type) { typeName = it.name }
                }
            }

            when (type) {
                NotebookMarkerType.AUTO -> Text(
                    "The first character and a stable color are chosen automatically.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                NotebookMarkerType.FOLDER,
                NotebookMarkerType.INITIAL,
                -> ColorPicker(color) { color = it }

                NotebookMarkerType.EMOJI -> {
                    Text("Choose an emoji", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QUICK_EMOJI.forEach { emoji ->
                            Surface(
                                color = if (firstGrapheme(value) == emoji) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .padding(bottom = 6.dp)
                                    .clickable { value = emoji },
                            ) {
                                Text(emoji, modifier = Modifier.padding(10.dp))
                            }
                        }
                    }
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it.take(16) },
                        label = { Text("Custom emoji") },
                        supportingText = { Text("The first emoji will be used") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ColorPicker(color) { color = it }
                }

                NotebookMarkerType.PRESET_PHOTO,
                NotebookMarkerType.CUSTOM_PHOTO,
                -> {
                    Text("Photo covers", style = MaterialTheme.typography.titleMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        maxItemsInEachRow = 4,
                    ) {
                        NOTEBOOK_PHOTO_PRESETS.forEach { preset ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .padding(bottom = 10.dp)
                                    .clickable {
                                        typeName = NotebookMarkerType.PRESET_PHOTO.name
                                        value = preset.key
                                    }
                                    .semantics { contentDescription = "Use ${preset.label} photo" },
                            ) {
                                Image(
                                    painter = painterResource(preset.drawable),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(68.dp)
                                        .clip(RoundedCornerShape(14.dp)),
                                )
                                Text(
                                    preset.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                    Button(
                        onClick = {
                            picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Choose from gallery") }
                    if (current.type == NotebookMarkerType.CUSTOM_PHOTO) {
                        OutlinedButton(
                            onClick = {
                                sourceMode = "existing"
                                error = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Re-crop current photo") }
                    }
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                onClick = {
                    viewModel.updateNotebookAppearance(
                        notebook.id,
                        NotebookAppearance(type = type, color = color, value = value),
                    )
                    onDismiss()
                },
                enabled = type != NotebookMarkerType.CUSTOM_PHOTO &&
                    (type != NotebookMarkerType.EMOJI || firstGrapheme(value).isNotBlank()) &&
                    (type != NotebookMarkerType.PRESET_PHOTO || NOTEBOOK_PHOTO_PRESETS.any { it.key == value }),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save appearance") }

            Spacer(Modifier.height(8.dp))
        }
    }

    sourceBitmap?.let { bitmap ->
        PhotoCropDialog(
            bitmap = bitmap,
            initialCrop = if (sourceMode == "existing") current.crop else NotebookImageStore.defaultCrop(bitmap),
            onCancel = {
                sourceBitmap = null
                sourceMode = null
            },
            onSave = { crop ->
                scope.launch {
                    runCatching { viewModel.saveCustomPhoto(notebook.id, bitmap, crop) }
                        .onSuccess { onDismiss() }
                        .onFailure { error = it.message ?: "Could not save the cropped photo" }
                    sourceBitmap = null
                    sourceMode = null
                }
            },
        )
    }
}

@Composable
private fun MarkerChoice(
    label: String,
    type: NotebookMarkerType,
    selected: NotebookMarkerType,
    onClick: (NotebookMarkerType) -> Unit,
) {
    FilterChip(
        selected = selected == type ||
            (label == "Photos" && selected == NotebookMarkerType.CUSTOM_PHOTO),
        onClick = { onClick(type) },
        label = { Text(label) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPicker(selected: Int, onSelect: (Int) -> Unit) {
    Text("Color", style = MaterialTheme.typography.titleMedium)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NOTEBOOK_MARKER_PALETTE.forEachIndexed { index, option ->
            val swatch = if (dark) option.darkContent else option.lightContent
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(swatch)
                    .clickable { onSelect(index) }
                    .semantics { contentDescription = "${option.label} marker color" },
            ) {
                if (selected == index) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = if (swatch.luminance() > 0.5f) Color.Black else Color.White,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoCropDialog(
    bitmap: Bitmap,
    initialCrop: NormalizedCrop,
    onCancel: () -> Unit,
    onSave: (NormalizedCrop) -> Unit,
) {
    var left by rememberSaveable(bitmap.width, bitmap.height) { mutableFloatStateOf(initialCrop.left) }
    var top by rememberSaveable(bitmap.width, bitmap.height) { mutableFloatStateOf(initialCrop.top) }
    var cropSize by rememberSaveable(bitmap.width, bitmap.height) { mutableFloatStateOf(initialCrop.size) }
    var viewportSize by remember { mutableIntStateOf(1) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val crop = NotebookImageStore.constrainCrop(bitmap, NormalizedCrop(left, top, cropSize))

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Crop notebook photo") },
                        navigationIcon = {
                            IconButton(onClick = onCancel) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                            }
                        },
                        actions = {
                            TextButton(onClick = { onSave(crop) }) { Text("Save") }
                        },
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "Pinch to zoom and drag to position",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.Black)
                            .testTag("photo-crop-canvas")
                            .onSizeChanged { viewportSize = min(it.width, it.height).coerceAtLeast(1) }
                            .pointerInput(bitmap, viewportSize) {
                                detectTransformGestures { centroid, pan, zoom, _ ->
                                    val old = NotebookImageStore.constrainCrop(
                                        bitmap,
                                        NormalizedCrop(left, top, cropSize),
                                    )
                                    val shortEdge = min(bitmap.width, bitmap.height).toFloat()
                                    val oldPx = old.size * shortEdge
                                    val newSize = (old.size / zoom).coerceIn(0.08f, 1f)
                                    val newPx = newSize * shortEdge
                                    val xRatio = (centroid.x / viewportSize).coerceIn(0f, 1f)
                                    val yRatio = (centroid.y / viewportSize).coerceIn(0f, 1f)
                                    val focalX = old.left * bitmap.width + xRatio * oldPx
                                    val focalY = old.top * bitmap.height + yRatio * oldPx
                                    val candidate = NormalizedCrop(
                                        left = (focalX - xRatio * newPx - pan.x / viewportSize * newPx) / bitmap.width,
                                        top = (focalY - yRatio * newPx - pan.y / viewportSize * newPx) / bitmap.height,
                                        size = newSize,
                                    )
                                    val safe = NotebookImageStore.constrainCrop(bitmap, candidate)
                                    left = safe.left
                                    top = safe.top
                                    cropSize = safe.size
                                }
                            },
                    ) {
                        val sourceSize = (crop.size * min(bitmap.width, bitmap.height)).roundToInt()
                        val sourceLeft = (crop.left * bitmap.width).roundToInt()
                        val sourceTop = (crop.top * bitmap.height).roundToInt()
                        drawImage(
                            image = image,
                            srcOffset = IntOffset(sourceLeft, sourceTop),
                            srcSize = IntSize(sourceSize, sourceSize),
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            filterQuality = FilterQuality.High,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val reset = NotebookImageStore.defaultCrop(bitmap)
                            left = reset.left
                            top = reset.top
                            cropSize = reset.size
                        },
                        modifier = Modifier.padding(top = 18.dp),
                    ) { Text("Reset crop") }
                }
            }
        }
    }
}

private fun Notebook.withPreview(type: NotebookMarkerType, color: Int, value: String): Notebook = copy(
    markerType = type.name,
    markerColor = color,
    markerValue = value,
)
