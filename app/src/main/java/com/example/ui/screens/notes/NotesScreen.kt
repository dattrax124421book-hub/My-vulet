package com.example.ui.screens.notes

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.Note
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(onBack: () -> Unit) {
    val viewModel: NotesViewModel = viewModel()
    val notes by viewModel.notes.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var selectedNote by remember { mutableStateOf<Note?>(null) }
    var isNewNoteMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var noteToDelete by remember { mutableStateOf<Note?>(null) }

    // If an editor is open (viewing/editing existing note or creating new note)
    if (selectedNote != null || isNewNoteMode) {
        val editingNote = selectedNote
        var title by remember(editingNote) { mutableStateOf(editingNote?.title ?: "") }
        var content by remember(editingNote) { mutableStateOf(editingNote?.content ?: "") }
        var isMonospace by remember { mutableStateOf(false) }

        val wordsCount = remember(content) {
            if (content.isBlank()) 0 else content.trim().split("\\s+".toRegex()).count { it.isNotEmpty() }
        }
        val charsCount = content.length

        val saveAndExit = {
            val trimmedTitle = title.trim()
            val trimmedContent = content.trim()
            if (trimmedTitle.isNotEmpty() || trimmedContent.isNotEmpty()) {
                val finalTitle = trimmedTitle.ifEmpty { "Untitled Note" }
                if (editingNote != null) {
                    viewModel.updateNote(editingNote.copy(title = finalTitle, content = content, timestamp = System.currentTimeMillis()))
                } else {
                    viewModel.addNote(finalTitle, content)
                }
            }
            selectedNote = null
            isNewNoteMode = false
        }

        BackHandler {
            saveAndExit()
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (editingNote != null) "Edit Note" else "New Note",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = saveAndExit) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back and Save")
                        }
                    },
                    actions = {
                        // Monospace toggle
                        IconButton(onClick = { isMonospace = !isMonospace }) {
                            Icon(
                                Icons.Default.Code,
                                contentDescription = "Toggle Monospace",
                                tint = if (isMonospace) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Copy Note
                        IconButton(onClick = {
                            val shareText = if (title.isNotBlank()) "$title\n\n$content" else content
                            clipboardManager.setText(AnnotatedString(shareText))
                            Toast.makeText(context, "Note copied to clipboard", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                        }

                        // Share Note
                        IconButton(onClick = {
                            val shareText = if (title.isNotBlank()) "$title\n\n$content" else content
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, title)
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Note"))
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }

                        // Delete Note (if existing)
                        if (editingNote != null) {
                            IconButton(onClick = {
                                viewModel.deleteNote(editingNote)
                                selectedNote = null
                                isNewNoteMode = false
                                Toast.makeText(context, "Note deleted", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Note", tint = MaterialTheme.colorScheme.error)
                            }
                        }

                        // Save checkmark button
                        IconButton(onClick = saveAndExit) {
                            Icon(Icons.Default.Check, contentDescription = "Save Note", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
            },
            bottomBar = {
                Surface(
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth().height(36.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "$wordsCount words • $charsCount characters",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (isMonospace) "Monospace" else "Proportional",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Title Field
                TextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text("Note Title", style = MaterialTheme.typography.titleLarge) },
                    textStyle = MaterialTheme.typography.titleLarge,
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Content Field: Unlimited length, smooth vertical scrolling, responsive
                TextField(
                    value = content,
                    onValueChange = { content = it },
                    placeholder = { Text("Start typing note details, code, logs, or thoughts...") },
                    textStyle = if (isMonospace) {
                        TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else {
                        MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp)
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
        return
    }

    // MAIN NOTES LIST VIEW
    val filteredNotes = remember(notes, searchQuery) {
        if (searchQuery.isBlank()) notes
        else notes.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.content.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notes & Snippets") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { isNewNoteMode = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Note")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search notes...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp)
            )

            if (filteredNotes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.NoteAlt,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outlineVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            if (searchQuery.isNotBlank()) "No notes match '$searchQuery'"
                            else "No notes yet. Tap + to write your first note!",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredNotes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            onClick = { selectedNote = note },
                            onDelete = { noteToDelete = note }
                        )
                    }
                }
            }
        }

        // Delete confirmation dialog
        if (noteToDelete != null) {
            val item = noteToDelete!!
            AlertDialog(
                onDismissRequest = { noteToDelete = null },
                title = { Text("Delete Note") },
                text = { Text("Are you sure you want to delete '${item.title.ifEmpty { "Untitled" }}'?") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.deleteNote(item)
                        noteToDelete = null
                        Toast.makeText(context, "Note deleted", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { noteToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun NoteCard(
    note: Note,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = note.title.ifBlank { "Untitled Note" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (note.content.isNotBlank()) {
                    Text(
                        text = note.content,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault()).format(Date(note.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    val words = if (note.content.isBlank()) 0 else note.content.trim().split("\\s+".toRegex()).count { it.isNotEmpty() }
                    Text(
                        text = "• $words words",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete Note",
                    tint = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}

