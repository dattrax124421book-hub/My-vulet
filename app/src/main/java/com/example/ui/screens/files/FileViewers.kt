package com.example.ui.screens.files

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipInputStream

// ==========================================
// 1. IMAGE VIEWER DIALOG
// ==========================================
@Composable
fun ImageViewerDialog(
    file: File,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onOpenWith: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var rotationAngle by remember { mutableFloatStateOf(0f) }
    var showInfo by remember { mutableStateOf(false) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var imageDimensions by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    val context = LocalContext.current

    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                val origWidth = options.outWidth
                val origHeight = options.outHeight

                if (origWidth <= 0 || origHeight <= 0) {
                    withContext(Dispatchers.Main) {
                        loadError = "Unsupported or corrupted image file."
                        isLoading = false
                    }
                    return@withContext
                }

                imageDimensions = Pair(origWidth, origHeight)

                // Downsample if huge (> 4096) to prevent OutOfMemoryError
                var sample = 1
                val maxDim = maxOf(origWidth, origHeight)
                while (maxDim / sample > 4096) {
                    sample *= 2
                }

                val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)

                withContext(Dispatchers.Main) {
                    if (bmp != null) {
                        bitmap = bmp
                    } else {
                        loadError = "Unable to decode image."
                    }
                    isLoading = false
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    loadError = t.localizedMessage ?: "Failed to read image"
                    isLoading = false
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Main image container
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.8f, 5.0f)
                            if (scale > 1f) {
                                offset += pan
                            } else {
                                offset = Offset.Zero
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLoading -> {
                        CircularProgressIndicator(color = Color.White)
                    }
                    loadError != null -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(Icons.Default.BrokenImage, null, tint = Color.Red, modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(loadError!!, color = Color.White, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = onOpenWith) {
                                Text("Open with another app")
                            }
                        }
                    }
                    bitmap != null -> {
                        Image(
                            bitmap = bitmap!!.asImageBitmap(),
                            contentDescription = file.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    rotationZ = rotationAngle,
                                    translationX = offset.x,
                                    translationY = offset.y
                                )
                        )
                    }
                }
            }

            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        file.name,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    imageDimensions?.let { (w, h) ->
                        Text("${w}x${h} • ${FileUtils.formatSize(context, file.length())}", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    }
                }
                IconButton(onClick = { rotationAngle = (rotationAngle + 90f) % 360f }) {
                    Icon(Icons.Default.RotateRight, "Rotate", tint = Color.White)
                }
                IconButton(onClick = { showInfo = !showInfo }) {
                    Icon(Icons.Default.Info, "Info", tint = Color.White)
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Default.Share, "Share", tint = Color.White)
                }
            }

            // Details overlay
            AnimatedVisibility(
                visible = showInfo,
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Image Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Name: ${file.name}", style = MaterialTheme.typography.bodySmall)
                        imageDimensions?.let {
                            Text("Resolution: ${it.first} x ${it.second} px", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Size: ${FileUtils.formatSize(context, file.length())}", style = MaterialTheme.typography.bodySmall)
                        Text("Path: ${file.absolutePath}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(file.lastModified()))
                        Text("Modified: $date", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// ==========================================
// 2. VIDEO PLAYER DIALOG
// ==========================================
@Composable
fun VideoPlayerDialog(
    file: File,
    onDismiss: () -> Unit,
    onOpenWith: () -> Unit
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var playerError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(videoViewRef, isPlaying) {
        while (isPlaying && videoViewRef != null) {
            try {
                currentPosition = videoViewRef?.currentPosition ?: 0
            } catch (e: Exception) {
                // ignore
            }
            delay(500)
        }
    }

    Dialog(
        onDismissRequest = {
            videoViewRef?.stopPlayback()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (playerError != null) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.ErrorOutline, null, tint = Color.Red, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Could not play video in built-in player.\nCodec may not be supported.", color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onOpenWith) {
                        Text("Open with external player")
                    }
                }
            } else {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            try {
                                if (!file.exists() || file.length() == 0L) {
                                    playerError = "Video file is empty or missing."
                                } else {
                                    setVideoPath(file.absolutePath)
                                    setOnPreparedListener { mp ->
                                        try {
                                            duration = mp.duration
                                            isPlaying = true
                                            mp.start()
                                        } catch (t: Throwable) {
                                            playerError = "Playback error: ${t.localizedMessage}"
                                        }
                                    }
                                    setOnCompletionListener {
                                        isPlaying = false
                                        currentPosition = duration
                                    }
                                    setOnErrorListener { _, what, extra ->
                                        playerError = "Playback error ($what, $extra)"
                                        true
                                    }
                                    videoViewRef = this
                                }
                            } catch (t: Throwable) {
                                playerError = "Unable to load video: ${t.localizedMessage}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Controls Top
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    videoViewRef?.stopPlayback()
                    onDismiss()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Text(
                    file.name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onOpenWith) {
                    Icon(Icons.Default.OpenInNew, "Open With", tint = Color.White)
                }
            }

            // Controls Bottom
            if (playerError == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(16.dp)
                ) {
                    Slider(
                        value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                        onValueChange = { frac ->
                            val target = (frac * duration).toInt()
                            currentPosition = target
                            videoViewRef?.seekTo(target)
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.Gray
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(formatDuration(currentPosition), color = Color.White, style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                val newPos = maxOf(0, currentPosition - 10000)
                                videoViewRef?.seekTo(newPos)
                                currentPosition = newPos
                            }) {
                                Icon(Icons.Default.Replay10, "Rewind 10s", tint = Color.White)
                            }
                            IconButton(
                                onClick = {
                                    if (isPlaying) {
                                        videoViewRef?.pause()
                                        isPlaying = false
                                    } else {
                                        videoViewRef?.start()
                                        isPlaying = true
                                    }
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    if (isPlaying) "Pause" else "Play",
                                    tint = Color.White
                                )
                            }
                            IconButton(onClick = {
                                val newPos = minOf(duration, currentPosition + 10000)
                                videoViewRef?.seekTo(newPos)
                                currentPosition = newPos
                            }) {
                                Icon(Icons.Default.Forward10, "Forward 10s", tint = Color.White)
                            }
                        }
                        Text(formatDuration(duration), color = Color.White, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// ==========================================
// 3. AUDIO PLAYER DIALOG
// ==========================================
@Composable
fun AudioPlayerDialog(
    file: File,
    onDismiss: () -> Unit,
    onOpenWith: () -> Unit
) {
    val context = LocalContext.current
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    var audioError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(file) {
        val player = MediaPlayer()
        try {
            if (!file.exists() || file.length() == 0L) {
                audioError = "Audio file is empty or missing."
            } else {
                player.setDataSource(file.absolutePath)
                player.setOnPreparedListener { mp ->
                    try {
                        duration = mp.duration
                        mp.start()
                        isPlaying = true
                        mediaPlayer = mp
                    } catch (t: Throwable) {
                        audioError = "Playback error: ${t.localizedMessage}"
                    }
                }
                player.setOnCompletionListener {
                    isPlaying = false
                    currentPosition = duration
                }
                player.setOnErrorListener { _, what, extra ->
                    audioError = "Playback error ($what, $extra)"
                    isPlaying = false
                    true
                }
                player.prepareAsync()
            }
        } catch (t: Throwable) {
            audioError = "Unable to open audio: ${t.localizedMessage}"
        }

        onDispose {
            try {
                mediaPlayer = null
                player.reset()
                player.release()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    LaunchedEffect(isPlaying, mediaPlayer) {
        while (isPlaying && mediaPlayer != null) {
            try {
                currentPosition = mediaPlayer?.currentPosition ?: 0
            } catch (e: Exception) {
                // ignore
            }
            delay(500)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Audiotrack, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Text("Audio Player", style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(FileUtils.formatSize(context, file.length()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))

                if (audioError != null) {
                    Text(audioError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else {
                    Slider(
                        value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                        onValueChange = { frac ->
                            val target = (frac * duration).toInt()
                            currentPosition = target
                            mediaPlayer?.seekTo(target)
                        }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatDuration(currentPosition), style = MaterialTheme.typography.bodySmall)
                        Text(formatDuration(duration), style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            val newPos = maxOf(0, currentPosition - 10000)
                            mediaPlayer?.seekTo(newPos)
                            currentPosition = newPos
                        }) {
                            Icon(Icons.Default.Replay10, "Rewind 10s")
                        }
                        Spacer(Modifier.width(16.dp))
                        FilledIconButton(
                            onClick = {
                                val mp = mediaPlayer ?: return@FilledIconButton
                                if (isPlaying) {
                                    mp.pause()
                                    isPlaying = false
                                } else {
                                    mp.start()
                                    isPlaying = true
                                }
                            },
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        IconButton(onClick = {
                            val newPos = minOf(duration, currentPosition + 10000)
                            mediaPlayer?.seekTo(newPos)
                            currentPosition = newPos
                        }) {
                            Icon(Icons.Default.Forward10, "Forward 10s")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenWith) {
                Text("Open With")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

// ==========================================
// 4. PDF VIEWER DIALOG
// ==========================================
@Composable
fun PdfViewerDialog(
    file: File,
    onDismiss: () -> Unit,
    onOpenWith: () -> Unit
) {
    val context = LocalContext.current
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var fileDescriptor by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var currentPageIndex by remember { mutableIntStateOf(0) }
    var totalPages by remember { mutableIntStateOf(0) }
    var currentPageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pdfError by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    fun renderPage(index: Int) {
        val r = renderer ?: return
        if (index < 0 || index >= r.pageCount) return
        try {
            val page = r.openPage(index)
            val scale = minOf(1f, 2048f / maxOf(page.width, page.height))
            val w = (page.width * scale).toInt().coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            currentPageBitmap = bmp
            currentPageIndex = index
        } catch (e: Exception) {
            pdfError = "Failed to render page: ${e.message}"
        }
    }

    DisposableEffect(file) {
        try {
            if (!file.exists() || file.length() == 0L) {
                pdfError = "PDF file is empty or missing."
                isLoading = false
            } else {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                fileDescriptor = pfd
                val pr = PdfRenderer(pfd)
                renderer = pr
                totalPages = pr.pageCount
                if (totalPages > 0) {
                    renderPage(0)
                } else {
                    pdfError = "PDF contains no pages."
                }
                isLoading = false
            }
        } catch (e: Exception) {
            pdfError = "Unable to open PDF: ${e.message}"
            isLoading = false
        }

        onDispose {
            try {
                renderer?.close()
                fileDescriptor?.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (totalPages > 0) {
                            Text("Page ${currentPageIndex + 1} of $totalPages", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = onOpenWith) {
                        Icon(Icons.Default.OpenInNew, "Open With")
                    }
                }
            },
            bottomBar = {
                if (totalPages > 1) {
                    Surface(
                        tonalElevation = 3.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { renderPage(currentPageIndex - 1) },
                                enabled = currentPageIndex > 0
                            ) {
                                Text("Previous")
                            }
                            Text("${currentPageIndex + 1} / $totalPages", fontWeight = FontWeight.Bold)
                            Button(
                                onClick = { renderPage(currentPageIndex + 1) },
                                enabled = currentPageIndex < totalPages - 1
                            ) {
                                Text("Next")
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color.DarkGray),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLoading -> CircularProgressIndicator(color = Color.White)
                    pdfError != null -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(Icons.Default.ErrorOutline, null, tint = Color.Red, modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(pdfError!!, color = Color.White, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = onOpenWith) {
                                Text("Open in external PDF app")
                            }
                        }
                    }
                    currentPageBitmap != null -> {
                        var scale by remember { mutableFloatStateOf(1f) }
                        var offset by remember { mutableStateOf(Offset.Zero) }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        scale = (scale * zoom).coerceIn(0.9f, 4.0f)
                                        if (scale > 1f) offset += pan else offset = Offset.Zero
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = currentPageBitmap!!.asImageBitmap(),
                                contentDescription = "PDF Page ${currentPageIndex + 1}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(
                                        scaleX = scale,
                                        scaleY = scale,
                                        translationX = offset.x,
                                        translationY = offset.y
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 5. ZIP VIEWER DIALOG (BROWSE ARCHIVE)
// ==========================================
@Composable
fun ZipViewerDialog(
    file: File,
    onDismiss: () -> Unit,
    onExtractAll: () -> Unit
) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<ZipEntryItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            try {
                if (!file.exists() || file.length() == 0L) {
                    withContext(Dispatchers.Main) {
                        errorMessage = "Archive is empty or missing."
                        isLoading = false
                    }
                    return@withContext
                }
                val list = mutableListOf<ZipEntryItem>()
                ZipInputStream(file.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    var count = 0
                    while (entry != null && count < 1000) {
                        list.add(
                            ZipEntryItem(
                                name = entry.name,
                                size = entry.size,
                                isDirectory = entry.isDirectory,
                                time = entry.time
                            )
                        )
                        count++
                        entry = zis.nextEntry
                    }
                }
                withContext(Dispatchers.Main) {
                    entries = list
                    isLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = "Failed to inspect archive: ${e.message}"
                    isLoading = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FolderZip, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Archive Contents", style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                Text(file.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(FileUtils.formatSize(context, file.length()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()

                when {
                    isLoading -> {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    errorMessage != null -> {
                        Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                    }
                    entries.isEmpty() -> {
                        Text("Archive is empty", modifier = Modifier.padding(16.dp))
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(entries) { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                                        null,
                                        modifier = Modifier.size(20.dp),
                                        tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(entry.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (!entry.isDirectory && entry.size >= 0) {
                                            Text(FileUtils.formatSize(context, entry.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onExtractAll) {
                Icon(Icons.Default.Unarchive, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Extract All")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

// ==========================================
// 6. FILE PROPERTIES DIALOG
// ==========================================
@Composable
fun FilePropertiesDialog(
    file: File,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var md5Hash by remember { mutableStateOf("Calculating...") }
    var folderSizeStr by remember { mutableStateOf(if (file.isDirectory) "Calculating..." else FileUtils.formatSize(context, file.length())) }
    var folderItemCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            if (file.isDirectory) {
                var total = 0L
                var count = 0
                file.walkTopDown().maxDepth(5).forEach { f ->
                    if (f.isFile) {
                        total += f.length()
                        count++
                    }
                }
                withContext(Dispatchers.Main) {
                    folderSizeStr = FileUtils.formatSize(context, total)
                    folderItemCount = count
                }
            } else {
                val hash = FileUtils.computeMD5(file)
                withContext(Dispatchers.Main) {
                    md5Hash = hash.ifEmpty { "Unavailable" }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (file.isDirectory) Icons.Default.Folder else Icons.Default.Info,
                    null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text("Properties", style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                PropertyRow("Name", file.name)
                PropertyRow("Type", if (file.isDirectory) "Directory" else FileUtils.getFileType(file).name)
                PropertyRow("MIME Type", FileUtils.getMimeType(file))
                PropertyRow("Path", file.absolutePath) {
                    clipboardManager.setText(AnnotatedString(file.absolutePath))
                    Toast.makeText(context, "Path copied", Toast.LENGTH_SHORT).show()
                }
                PropertyRow("Size", folderSizeStr)
                if (file.isDirectory) {
                    PropertyRow("Contains", "$folderItemCount files")
                }
                val date = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault()).format(Date(file.lastModified()))
                PropertyRow("Last Modified", date)
                PropertyRow("Permissions", "Readable: ${file.canRead()} | Writable: ${file.canWrite()}")
                PropertyRow("Hidden", if (file.isHidden) "Yes" else "No")

                if (!file.isDirectory) {
                    PropertyRow("MD5 Checksum", md5Hash) {
                        if (md5Hash != "Calculating..." && md5Hash != "Unavailable") {
                            clipboardManager.setText(AnnotatedString(md5Hash))
                            Toast.makeText(context, "Checksum copied", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
private fun PropertyRow(label: String, value: String, onClickValue: (() -> Unit)? = null) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                fontFamily = if (label.contains("Path") || label.contains("Checksum")) FontFamily.Monospace else FontFamily.Default
            )
            if (onClickValue != null) {
                IconButton(onClick = onClickValue, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.ContentCopy, "Copy", modifier = Modifier.size(16.dp))
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

// ==========================================
// 7. STORAGE ANALYTICS & DUPLICATE CLEANER
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageAnalyticsSheet(
    analytics: StorageAnalytics,
    isLoading: Boolean,
    onDeleteFile: (File) -> Unit,
    onDeleteEmptyFolder: (File) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var fileToDelete by remember { mutableStateOf<File?>(null) }

    if (fileToDelete != null) {
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Confirm Deletion") },
            text = { Text("Are you sure you want to permanently delete '${fileToDelete?.name}'?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        fileToDelete?.let { onDeleteFile(it) }
                        fileToDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) { Text("Cancel") }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.PieChart, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text("Storage Analytics & Cleaner", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))

            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Overview") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Large Files") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Duplicates") })
                Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text("Empty Folders") })
            }
            Spacer(Modifier.height(12.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("Analyzing storage...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                when (selectedTab) {
                    0 -> StorageOverviewContent(analytics, context)
                    1 -> LargeFilesContent(analytics.largestFiles, context) { fileToDelete = it }
                    2 -> DuplicateFilesContent(analytics.duplicateGroups, context) { fileToDelete = it }
                    3 -> EmptyFoldersContent(analytics.emptyFolders) { onDeleteEmptyFolder(it) }
                }
            }
        }
    }
}

@Composable
private fun StorageOverviewContent(analytics: StorageAnalytics, context: Context) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Storage Breakdown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                val usedPct = if (analytics.totalBytes > 0) (analytics.usedBytes.toFloat() / analytics.totalBytes) else 0f
                LinearProgressIndicator(
                    progress = { usedPct.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Used: ${FileUtils.formatSize(context, analytics.usedBytes)} (${(usedPct * 100).toInt()}%)", style = MaterialTheme.typography.bodySmall)
                    Text("Free: ${FileUtils.formatSize(context, analytics.freeBytes)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Categories", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        CategoryRow("Images", analytics.imageBytes, Icons.Default.Image, Color(0xFF4CAF50), context)
        CategoryRow("Videos", analytics.videoBytes, Icons.Default.Videocam, Color(0xFFE91E63), context)
        CategoryRow("Audio", analytics.audioBytes, Icons.Default.Audiotrack, Color(0xFFFF9800), context)
        CategoryRow("Documents", analytics.documentBytes, Icons.Default.Description, Color(0xFF2196F3), context)
        CategoryRow("Archives", analytics.archiveBytes, Icons.Default.FolderZip, Color(0xFF9C27B0), context)
        CategoryRow("Code & Scripts", analytics.codeBytes, Icons.Default.Code, Color(0xFF00BCD4), context)
        CategoryRow("APKs", analytics.apkBytes, Icons.Default.Android, Color(0xFF8BC34A), context)
        CategoryRow("Other Files", analytics.otherBytes, Icons.Default.Folder, Color(0xFF9E9E9E), context)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CategoryRow(title: String, bytes: Long, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, context: Context) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(color.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(FileUtils.formatSize(context, bytes), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LargeFilesContent(files: List<File>, context: Context, onDelete: (File) -> Unit) {
    if (files.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No large files found (> 10MB)")
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(files) { file ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${FileUtils.formatSize(context, file.length())} • ${file.parent ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { onDelete(file) }) {
                        Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun DuplicateFilesContent(groups: List<List<File>>, context: Context, onDelete: (File) -> Unit) {
    if (groups.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No duplicate files detected.")
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(groups) { group ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Duplicate Set (${group.size} copies - ${FileUtils.formatSize(context, group[0].length())} each)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(6.dp))
                        group.forEachIndexed { index, file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "${if (index == 0) "[Original] " else "[Copy] "}${file.name}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(file.absolutePath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (index > 0) {
                                    IconButton(onClick = { onDelete(file) }) {
                                        Icon(Icons.Default.Delete, "Delete copy", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyFoldersContent(folders: List<File>, onDelete: (File) -> Unit) {
    if (folders.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No empty folders found.")
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(folders) { folder ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(folder.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(folder.absolutePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { onDelete(folder) }) {
                        Icon(Icons.Default.Delete, "Delete empty folder", tint = MaterialTheme.colorScheme.error)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

private fun formatDuration(millis: Int): String {
    val seconds = (millis / 1000) % 60
    val minutes = (millis / (1000 * 60)) % 60
    val hours = (millis / (1000 * 60 * 60))
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}
