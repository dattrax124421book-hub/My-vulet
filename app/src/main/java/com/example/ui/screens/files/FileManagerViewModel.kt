package com.example.ui.screens.files

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.content.ContentValues
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.VaultItem
import com.example.security.KeystoreHelper
import com.example.ui.screens.vault.VaultViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import android.util.Base64

data class FileSearchQuery(
    val isActive: Boolean = false,
    val nameMatch: String = "",
    val extensionMatch: String = "",
    val minSizeKb: Long? = null,
    val maxSizeKb: Long? = null
)

class FileManagerViewModel : ViewModel() {
    private val _currentDir = MutableStateFlow<File?>(null)
    val currentDir = _currentDir.asStateFlow()

    private val _files = MutableStateFlow<List<File>>(emptyList())
    val files = _files.asStateFlow()

    private val _selectedFiles = MutableStateFlow<Set<File>>(emptySet())
    val selectedFiles = _selectedFiles.asStateFlow()

    private val _searchQuery = MutableStateFlow(FileSearchQuery())
    val searchQuery = _searchQuery.asStateFlow()
    
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing = _isProcessing.asStateFlow()

    fun init(rootDir: File) {
        if (_currentDir.value == null) {
            _currentDir.value = rootDir
            refresh()
        }
    }

    fun navigateTo(dir: File) {
        _currentDir.value = dir
        _selectedFiles.value = emptySet()
        refresh()
    }

    fun navigateUp(rootDir: File) {
        val parent = _currentDir.value?.parentFile
        if (parent != null && _currentDir.value?.absolutePath != rootDir.absolutePath) {
            _currentDir.value = parent
            _selectedFiles.value = emptySet()
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = _currentDir.value ?: return@launch
            val allFiles = dir.listFiles()?.toList() ?: emptyList()
            
            val query = _searchQuery.value
            val filtered = if (query.isActive) {
                allFiles.filter { file ->
                    val nameOk = query.nameMatch.isEmpty() || file.name.contains(query.nameMatch, ignoreCase = true)
                    val extOk = query.extensionMatch.isEmpty() || file.extension.equals(query.extensionMatch, ignoreCase = true)
                    val minSizeOk = query.minSizeKb == null || file.length() >= query.minSizeKb * 1024
                    val maxSizeOk = query.maxSizeKb == null || file.length() <= query.maxSizeKb * 1024
                    
                    nameOk && extOk && (file.isDirectory || (minSizeOk && maxSizeOk))
                }
            } else {
                allFiles
            }
            
            val sorted = filtered.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            _files.value = sorted
        }
    }

    fun setSearchQuery(query: FileSearchQuery) {
        _searchQuery.value = query
        refresh()
    }

    fun toggleSelection(file: File) {
        _selectedFiles.update { current ->
            if (current.contains(file)) current - file else current + file
        }
    }

    fun selectAll() {
        _selectedFiles.value = _files.value.toSet()
    }

    fun clearSelection() {
        _selectedFiles.value = emptySet()
    }

    fun deleteSelected(onResult: (String) -> Unit) {
        val toDelete = _selectedFiles.value.toList()
        if (toDelete.isEmpty()) return
        
        _isProcessing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            var success = 0
            var fail = 0
            for (file in toDelete) {
                if (file.deleteRecursively()) {
                    success++
                } else {
                    fail++
                }
            }
            withContext(Dispatchers.Main) {
                _selectedFiles.value = emptySet()
                _isProcessing.value = false
                refresh()
                onResult("Deleted $success files, failed $fail")
            }
        }
    }
    
    fun extractZip(zipFile: File, targetDir: File, onResult: (String) -> Unit) {
        _isProcessing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ZipInputStream(zipFile.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    val targetDirPath = targetDir.canonicalPath
                    while (entry != null) {
                        val resolvedFile = File(targetDir, entry.name)
                        if (!resolvedFile.canonicalPath.startsWith(targetDirPath + File.separator)) {
                            throw SecurityException("Zip Slip vulnerability detected: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            resolvedFile.mkdirs()
                        } else {
                            resolvedFile.parentFile?.mkdirs()
                            resolvedFile.outputStream().use { out ->
                                zis.copyTo(out)
                            }
                        }
                        entry = zis.nextEntry
                    }
                }
                withContext(Dispatchers.Main) {
                    _isProcessing.value = false
                    refresh()
                    onResult("Extracted successfully")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isProcessing.value = false
                    onResult("Extraction failed: ${e.message}")
                }
            }
        }
    }
    
    fun zipSelected(context: Context, safUri: String?, zipName: String, onResult: (String) -> Unit) {
        val filesToZip = _selectedFiles.value.toList()
        if (filesToZip.isEmpty()) return
        
        _isProcessing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var outUri: Uri? = null
                var outputStream: java.io.OutputStream? = null
                val resolver = context.contentResolver
                
                if (!safUri.isNullOrEmpty()) {
                    val dir = DocumentFile.fromTreeUri(context, Uri.parse(safUri))
                    if (dir != null) {
                        val newFile = dir.createFile("application/zip", zipName)
                        if (newFile != null) {
                            outUri = newFile.uri
                            outputStream = resolver.openOutputStream(outUri)
                        } else throw Exception("Failed to create file in SAF directory")
                    } else throw Exception("Export directory not found or permission denied")
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, zipName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DevVault")
                    }
                    outUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (outUri != null) {
                        outputStream = resolver.openOutputStream(outUri)
                    } else throw Exception("Failed to create MediaStore entry")
                } else throw Exception("No valid export location")
                
                if (outputStream == null) throw Exception("Could not open output stream")
                
                ZipOutputStream(outputStream).use { zos ->
                    for (file in filesToZip) {
                        zipFile(file, file.name, zos)
                    }
                }
                
                withContext(Dispatchers.Main) {
                    _selectedFiles.value = emptySet()
                    _isProcessing.value = false
                    onResult("Created ZIP successfully at ${if (safUri.isNullOrEmpty()) "Downloads/DevVault" else "Export Folder"}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isProcessing.value = false
                    onResult("ZIP creation failed: ${e.message}")
                }
            }
        }
    }
    
    private fun zipFile(fileToZip: File, fileName: String, zos: ZipOutputStream) {
        if (fileToZip.isHidden) return
        if (fileToZip.isDirectory) {
            val children = fileToZip.listFiles()
            if (children != null) {
                for (childFile in children) {
                    zipFile(childFile, fileName + "/" + childFile.name, zos)
                }
            }
            return
        }
        val zipEntry = ZipEntry(fileName)
        zos.putNextEntry(zipEntry)
        fileToZip.inputStream().use { it.copyTo(zos) }
        zos.closeEntry()
    }

    fun moveToVault(context: Context, vaultViewModel: VaultViewModel, onResult: (String) -> Unit) {
        val filesToMove = _selectedFiles.value.toList()
        if (filesToMove.isEmpty()) return
        
        _isProcessing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val keystoreHelper = KeystoreHelper()
                val vaultDir = File(context.filesDir, "vault_files")
                if (!vaultDir.exists()) vaultDir.mkdirs()
                
                var successCount = 0
                for (file in filesToMove) {
                    if (file.isDirectory) continue // Only vaulting individual files for now
                    val bytes = file.readBytes()
                    val (iv, encryptedData) = keystoreHelper.encrypt(bytes)
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
                    successCount++
                }
                
                withContext(Dispatchers.Main) {
                    _selectedFiles.value = emptySet()
                    _isProcessing.value = false
                    refresh()
                    onResult("Moved $successCount file(s) to Vault")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isProcessing.value = false
                    onResult("Failed to move to vault: ${e.message}")
                }
            }
        }
    }
}
