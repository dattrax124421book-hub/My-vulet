package com.example.ui.screens.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.VaultItem
import com.example.security.KeystoreHelper
import com.example.ui.screens.vault.VaultViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Base64
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(onBack: () -> Unit, rootDir: File, onNavigateToEditor: (String) -> Unit = {}) {
    var currentDir by remember { mutableStateOf(rootDir) }
    var files by remember { mutableStateOf(currentDir.listFiles()?.toList() ?: emptyList()) }
    val vaultViewModel: VaultViewModel = viewModel()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    fun refreshFiles() {
        files = currentDir.listFiles()?.toList()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
    }

    LaunchedEffect(currentDir) {
        refreshFiles()
    }

    fun moveToVault(file: File) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val bytes = file.readBytes()
                val keystoreHelper = KeystoreHelper()
                val (iv, encryptedData) = keystoreHelper.encrypt(bytes)
                
                val vaultDir = File(context.filesDir, "vault_files")
                if (!vaultDir.exists()) vaultDir.mkdirs()
                
                val destFile = File(vaultDir, UUID.randomUUID().toString())
                destFile.writeBytes(iv + encryptedData)
                
                val (nameIv, nameEncrypted) = keystoreHelper.encrypt(file.name.toByteArray())
                val encryptedNameStr = "${Base64.encodeToString(nameIv, Base64.NO_WRAP)}:${Base64.encodeToString(nameEncrypted, Base64.NO_WRAP)}"
                
                vaultViewModel.addVaultItem(
                    VaultItem(
                        encryptedFilename = encryptedNameStr,
                        encryptedPath = destFile.absolutePath
                    )
                )
                file.delete()
                withContext(Dispatchers.Main) {
                    refreshFiles()
                    Toast.makeText(context, "Moved to Vault", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to move: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentDir.name.ifEmpty { "Internal Storage" }) },
                navigationIcon = {
                    if (currentDir.absolutePath != rootDir.absolutePath) {
                        IconButton(onClick = { currentDir = currentDir.parentFile ?: rootDir }) {
                            Text("Back")
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp)
        ) {
            if (files.isEmpty()) {
                item { Text("Folder is empty", modifier = Modifier.padding(16.dp)) }
            }
            items(files) { file ->
                FileItem(
                    file = file,
                    onClick = {
                        if (file.isDirectory) {
                            currentDir = file
                        } else {
                            onNavigateToEditor(file.absolutePath)
                        }
                    },
                    onMoveToVault = { moveToVault(file) }
                )
                Divider()
            }
        }
    }
}

@Composable
fun FileItem(file: File, onClick: () -> Unit, onMoveToVault: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
            contentDescription = null,
            tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = file.name, style = MaterialTheme.typography.bodyLarge)
            Row {
                val sizeStr = if (file.isDirectory) "${file.listFiles()?.size ?: 0} items" else "${file.length() / 1024} KB"
                val dateStr = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
                Text(
                    text = "$dateStr • $sizeStr",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!file.isDirectory) {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Move to Vault") },
                        onClick = {
                            showMenu = false
                            onMoveToVault()
                        }
                    )
                }
            }
        }
    }
}
