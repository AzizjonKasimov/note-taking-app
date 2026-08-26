package com.azizjon.notes.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.azizjon.notes.NotesApplication
import com.azizjon.notes.R
import com.azizjon.notes.data.Notebook
import com.azizjon.notes.data.NotebookMarkerType
import com.azizjon.notes.data.appearance
import com.azizjon.notes.data.automaticMarkerColor
import com.azizjon.notes.data.notebookMarkerInitial

data class NotebookPhotoPreset(
    val key: String,
    val label: String,
    @DrawableRes val drawable: Int,
)

val NOTEBOOK_PHOTO_PRESETS = listOf(
    NotebookPhotoPreset("mountain", "Mountain", R.drawable.notebook_photo_mountain),
    NotebookPhotoPreset("forest", "Forest", R.drawable.notebook_photo_forest),
    NotebookPhotoPreset("ocean", "Ocean", R.drawable.notebook_photo_ocean),
    NotebookPhotoPreset("desert", "Desert", R.drawable.notebook_photo_desert),
    NotebookPhotoPreset("city", "City", R.drawable.notebook_photo_city),
    NotebookPhotoPreset("architecture", "Architecture", R.drawable.notebook_photo_architecture),
    NotebookPhotoPreset("workspace", "Workspace", R.drawable.notebook_photo_workspace),
    NotebookPhotoPreset("books", "Books", R.drawable.notebook_photo_books),
    NotebookPhotoPreset("coffee", "Coffee", R.drawable.notebook_photo_coffee),
    NotebookPhotoPreset("food", "Food", R.drawable.notebook_photo_food),
    NotebookPhotoPreset("galaxy", "Galaxy", R.drawable.notebook_photo_galaxy),
    NotebookPhotoPreset("abstract", "Abstract", R.drawable.notebook_photo_abstract),
)

data class MarkerPaletteColor(
    val label: String,
    val lightContainer: Color,
    val lightContent: Color,
    val darkContainer: Color,
    val darkContent: Color,
)

val NOTEBOOK_MARKER_PALETTE = listOf(
    MarkerPaletteColor("Red", Color(0xFFFFDAD6), Color(0xFF8C1D18), Color(0xFF6F2925), Color(0xFFFFB4AB)),
    MarkerPaletteColor("Orange", Color(0xFFFFDCC2), Color(0xFF7D3500), Color(0xFF653315), Color(0xFFFFB77C)),
    MarkerPaletteColor("Amber", Color(0xFFFFE08A), Color(0xFF614100), Color(0xFF594515), Color(0xFFFFD66B)),
    MarkerPaletteColor("Green", Color(0xFFB9F0C1), Color(0xFF155D2C), Color(0xFF245B33), Color(0xFF9BD6A5)),
    MarkerPaletteColor("Teal", Color(0xFFA8EFEC), Color(0xFF005E5C), Color(0xFF155B59), Color(0xFF8CD5D2)),
    MarkerPaletteColor("Blue", Color(0xFFD4E3FF), Color(0xFF284777), Color(0xFF2F486D), Color(0xFFA8C8FF)),
    MarkerPaletteColor("Indigo", Color(0xFFE1E0FF), Color(0xFF42447C), Color(0xFF43436B), Color(0xFFC2C1FF)),
    MarkerPaletteColor("Violet", Color(0xFFF1DBFF), Color(0xFF6B3782), Color(0xFF573663), Color(0xFFE5B6F8)),
)

@Composable
fun NotebookMarker(
    notebook: Notebook,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val appearance = notebook.appearance()
    val colorIndex = if (appearance.type == NotebookMarkerType.AUTO) {
        automaticMarkerColor(notebook.id)
    } else {
        appearance.color
    }
    val palette = NOTEBOOK_MARKER_PALETTE[colorIndex]
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val container = if (dark) palette.darkContainer else palette.lightContainer
    val content = if (dark) palette.darkContent else palette.lightContent
    val shape = RoundedCornerShape(size / 3f)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(container),
    ) {
        when (appearance.type) {
            NotebookMarkerType.FOLDER -> Icon(
                painter = painterResource(R.drawable.ic_notebook_folder),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(size * 0.68f),
            )

            NotebookMarkerType.EMOJI -> Text(
                text = appearance.value,
                fontSize = (size.value * 0.56f).sp,
            )

            NotebookMarkerType.PRESET_PHOTO -> {
                val preset = NOTEBOOK_PHOTO_PRESETS.firstOrNull { it.key == appearance.value }
                if (preset != null) MarkerImage(preset.drawable, size) else InitialMarker(notebook, content, size)
            }

            NotebookMarkerType.CUSTOM_PHOTO -> CustomPhotoMarker(notebook, size, content)
            NotebookMarkerType.AUTO,
            NotebookMarkerType.INITIAL,
            -> InitialMarker(notebook, content, size)
        }
    }
}

@Composable
private fun InitialMarker(notebook: Notebook, color: Color, size: Dp) {
    Text(
        text = notebookMarkerInitial(notebook.name),
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = (size.value * 0.47f).sp,
        maxLines = 1,
    )
}

@Composable
private fun MarkerImage(@DrawableRes drawable: Int, size: Dp) {
    Image(
        painter = painterResource(drawable),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(size),
    )
}

@Composable
private fun CustomPhotoMarker(notebook: Notebook, size: Dp, fallbackColor: Color) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as NotesApplication
    val crop by produceState<android.graphics.Bitmap?>(
        initialValue = null,
        notebook.id,
        notebook.cropLeft,
        notebook.cropTop,
        notebook.cropSize,
    ) {
        value = app.notebookImageStore.loadCrop(notebook.id)
    }
    if (crop != null) {
        Image(
            bitmap = crop!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size),
        )
    } else {
        InitialMarker(notebook, fallbackColor, size)
    }
}

@Composable
fun AllNotesMarker(size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3f))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_all_notes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(size * 0.7f),
        )
    }
}
