package com.example.ui.screens.files

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.VaultItem
import com.example.security.KeystoreHelper
import com.example.ui.screens.vault.VaultViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class FileSearchQuery(
    val isActive: Boolean = false,
    val nameMatch: String = "",
    val extensionMatch: String = "",
    val minSizeKb: Long? = null,
    val maxSizeKb: Long? = null,
    val isRecursive: Boolean = true,
    val dateFilter: String = "ANY" // ANY, TODAY, WEEK, MONTH
)

class FileManagerViewModel : ViewModel() {
    private var baseRootDir: File? = null

    private val _currentDir = MutableStateFlow<File?>(null)
    val currentDir = _currentDir.asStateFlow()

    private val _files = MutableStateFlow<List<File>>(emptyList())
    val files = _files.asStateFlow()

    private val _selectedFiles = MutableStateFlow<Set<File>>(emptySet())
    val selectedFiles = _selectedFiles.asStateFlow()

    private val _searchQuery = MutableStateFlow(FileSearchQuery())
    val searchQuery = _searchQuery.asStateFlow()

    private val _selectedCategory = MutableStateFlow(FileCategory.ALL)
    val selectedCategory = _selectedCategory.asStateFlow()

    private val _sortBy = MutableStateFlow(SortBy.NAME_ASC)
    val sortBy = _sortBy.asStateFlow()

    private val _viewMode = MutableStateFlow(FileViewMode.LIST)
    val viewMode = _viewMode.asStateFlow()

    private val _showHidden = MutableStateFlow(false)
    val showHidden = _showHidden.asStateFlow()

    private val _clipboard = MutableStateFlow<FileClipboard?>(null)
    val clipboard = _clipboard.asStateFlow()

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites = _favorites.asStateFlow()

    private val _recentFiles = MutableStateFlow<List<File>>(emptyList())
    val recentFiles = _recentFiles.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing = _isProcessing.asStateFlow()

    private val _processingMessage = MutableStateFlow("Processing...")
    val processingMessage = _processingMessage.asStateFlow()

    private val _analytics = MutableStateFlow(StorageAnalytics())
    val analytics = _analytics.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing = _isAnalyzing.asStateFlow()

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory = _searchHistory.asStateFlow()

    private var searchJob: Job? = null

    fun init(rootDir: File, initialFavorites: Set<String> = emptySet(), initialShowHidden: Boolean = false) {
        baseRootDir = rootDir
        _favorites.value = initialFavorites
        _showHidden.value = initialShowHidden
        if (_currentDir.value == null) {
            _currentDir.value = rootDir
            populateStarterFilesIfNeeded(rootDir)
            refresh()
        }
    }

    fun switchStorageLocation(newRoot: File) {
        baseRootDir = newRoot
        _currentDir.value = newRoot
        _selectedFiles.value = emptySet()
        populateStarterFilesIfNeeded(newRoot)
        refresh()
    }

    private fun populateStarterFilesIfNeeded(dir: File) {
        try {
            if (!dir.exists()) dir.mkdirs()
            val list = dir.listFiles()
            if (list == null || list.isEmpty()) {
                val docs = File(dir, "Documents").apply { mkdirs() }
                File(docs, "ReadMe.txt").writeText("Welcome to DevVault File Manager!\nOffline secure storage is active.\nYou can create, edit, extract ZIPs, and encrypt files here.")
                val projects = File(dir, "Projects").apply { mkdirs() }
                File(projects, "SampleCode.kt").writeText("// DevVault Code Editor Sample\nfun main() {\n    println(\"DevVault File Manager is active!\")\n}")
                File(dir, "QuickNotes.txt").writeText("DevVault File Explorer active.\nSupports multi-select, ZIP, code editing, and vault transfer.")
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    fun setFavorites(favs: Set<String>) {
        _favorites.value = favs
    }

    fun setShowHidden(show: Boolean) {
        _showHidden.value = show
        refresh()
    }

    fun setCategory(category: FileCategory) {
        _selectedCategory.value = category
        refresh()
    }

    fun setSortBy(sort: SortBy) {
        _sortBy.value = sort
        refresh()
    }

    fun toggleViewMode() {
        _viewMode.update { if (it == FileViewMode.LIST) FileViewMode.GRID else FileViewMode.LIST }
    }

    fun navigateTo(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        _currentDir.value = dir
        _selectedFiles.value = emptySet()
        refresh()
    }

    fun navigateUp(rootDir: File) {
        val cur = _currentDir.value ?: return
        val parent = cur.parentFile
        if (parent != null && cur.absolutePath != rootDir.absolutePath) {
            _currentDir.value = parent
            _selectedFiles.value = emptySet()
            refresh()
        }
    }

    fun getBreadcrumbs(rootDir: File): List<File> {
        val current = _currentDir.value ?: return emptyList()
        val list = mutableListOf<File>()
        var curr: File? = current
        val rootPath = rootDir.absolutePath
        while (curr != null) {
            list.add(0, curr)
            if (curr.absolutePath == rootPath || curr.parentFile == null) break
            curr = curr.parentFile
        }
        return list
    }

    fun refresh() {
        val query = _searchQuery.value
        if (query.isActive && query.nameMatch.isNotBlank()) {
            executeSearch(query)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val dir = _currentDir.value ?: return@launch
            val rawFiles = dir.listFiles()?.toList() ?: emptyList()
            val hiddenAllowed = _showHidden.value
            val category = _selectedCategory.value

            val filtered = rawFiles.filter { file ->
                if (!hiddenAllowed && file.name.startsWith(".")) return@filter false
                if (category == FileCategory.ALL) return@filter true
                if (file.isDirectory) return@filter true // keep directories accessible

                val type = FileUtils.getFileType(file)
                when (category) {
                    FileCategory.IMAGES -> type == FileType.IMAGE
                    FileCategory.VIDEOS -> type == FileType.VIDEO
                    FileCategory.AUDIO -> type == FileType.AUDIO
                    FileCategory.DOCUMENTS -> type == FileType.DOCUMENT || type == FileType.PDF
                    FileCategory.DOWNLOADS -> file.parentFile?.name.equals("Download", ignoreCase = true) || file.parentFile?.name.equals("Downloads", ignoreCase = true)
                    FileCategory.ARCHIVES -> type == FileType.ARCHIVE
                    FileCategory.CODE -> type == FileType.CODE
                    FileCategory.APKS -> type == FileType.APK
                    else -> true
                }
            }

            val sorted = sortFiles(filtered, _sortBy.value)
            withContext(Dispatchers.Main) {
                _files.value = sorted
            }
        }
    }

    private fun sortFiles(list: List<File>, sort: SortBy): List<File> {
        return when (sort) {
            SortBy.NAME_ASC -> list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            SortBy.NAME_DESC -> list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).reversed()
            SortBy.DATE_NEWEST -> list.sortedWith(compareBy({ !it.isDirectory }, { -it.lastModified() }))
            SortBy.DATE_OLDEST -> list.sortedWith(compareBy({ !it.isDirectory }, { it.lastModified() }))
            SortBy.SIZE_LARGEST -> list.sortedWith(compareBy({ !it.isDirectory }, { -it.length() }))
            SortBy.SIZE_SMALLEST -> list.sortedWith(compareBy({ !it.isDirectory }, { it.length() }))
            SortBy.TYPE -> list.sortedWith(compareBy({ !it.isDirectory }, { it.extension.lowercase() }, { it.name.lowercase() }))
        }
    }

    fun setSearchQuery(query: FileSearchQuery) {
        _searchQuery.value = query
        if (!query.isActive || query.nameMatch.isBlank()) {
            searchJob?.cancel()
            refresh()
        } else {
            executeSearch(query)
            if (query.nameMatch.isNotBlank() && !_searchHistory.value.contains(query.nameMatch)) {
                _searchHistory.update { (listOf(query.nameMatch) + it).take(10) }
            }
        }
    }

    private fun executeSearch(query: FileSearchQuery) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val root = if (query.isRecursive) (baseRootDir ?: _currentDir.value) else _currentDir.value
            if (root == null) return@launch

            val results = mutableListOf<File>()
            val now = System.currentTimeMillis()
            val dayMs = 24 * 60 * 60 * 1000L

            val minTime = when (query.dateFilter) {
                "TODAY" -> now - dayMs
                "WEEK" -> now - 7 * dayMs
                "MONTH" -> now - 30 * dayMs
                else -> 0L
            }

            fun inspectDir(folder: File, depth: Int) {
                if (depth > 8) return // safe max depth
                val children = folder.listFiles() ?: return
                for (child in children) {
                    if (!_showHidden.value && child.name.startsWith(".")) continue

                    val nameOk = query.nameMatch.isBlank() || child.name.contains(query.nameMatch, ignoreCase = true)
                    val extOk = query.extensionMatch.isBlank() || child.extension.equals(query.extensionMatch, ignoreCase = true)
                    val dateOk = minTime == 0L || child.lastModified() >= minTime
                    val sizeOk = if (child.isDirectory) true else {
                        val kb = child.length() / 1024
                        (query.minSizeKb == null || kb >= query.minSizeKb) && (query.maxSizeKb == null || kb <= query.maxSizeKb)
                    }

                    if (nameOk && extOk && dateOk && sizeOk) {
                        results.add(child)
                    }

                    if (child.isDirectory && query.isRecursive) {
                        inspectDir(child, depth + 1)
                    }
                }
            }

            inspectDir(root, 0)
            val sorted = sortFiles(results, _sortBy.value)
            withContext(Dispatchers.Main) {
                _files.value = sorted
            }
        }
    }

    fun toggleSelection(file: File) {
        _selectedFiles.update { current ->
            if (current.contains(file)) current - file else current + file
        }
    }

    fun selectAll() {
        _selectedFiles.value = _files.value.toSet()
    }

    fun invertSelection() {
        val all = _files.value.toSet()
        _selectedFiles.update { current -> all - current }
    }

    fun clearSelection() {
        _selectedFiles.value = emptySet()
    }

    fun copySelected() {
        val selected = _selectedFiles.value.toList()
        if (selected.isNotEmpty()) {
            _clipboard.value = FileClipboard(ClipboardAction.COPY, selected)
            clearSelection()
        }
    }

    fun cutSelected() {
        val selected = _selectedFiles.value.toList()
        if (selected.isNotEmpty()) {
            _clipboard.value = FileClipboard(ClipboardAction.CUT, selected)
            clearSelection()
        }
    }

    fun clearClipboard() {
        _clipboard.value = null
    }

    fun pasteClipboard(targetDir: File, onResult: (String) -> Unit) {
        val clip = _clipboard.value ?: return
        if (!targetDir.exists() || !targetDir.isDirectory) {
            onResult("Target folder does not exist")
            return
        }

        _isProcessing.value = true
        _processingMessage.value = if (clip.action == ClipboardAction.COPY) "Copying files..." else "Moving files..."

        viewModelScope.launch(Dispatchers.IO) {
            var success = 0
            var fail = 0

            for (source in clip.files) {
                if (!source.exists()) {
                    fail++
                    continue
                }
                val dest = File(targetDir, source.name)
                try {
                    if (clip.action == ClipboardAction.COPY) {
                        if (source.isDirectory) {
                            source.copyRecursively(dest, overwrite = true)
                        } else {
                            source.copyTo(dest, overwrite = true)
                        }
                    } else { // CUT / MOVE
                        if (source.renameTo(dest)) {
                            // moved successfully
                        } else {
                            if (source.isDirectory) {
                                source.copyRecursively(dest, overwrite = true)
                                source.deleteRecursively()
                            } else {
                                source.copyTo(dest, overwrite = true)
                                source.delete()
                            }
                        }
                    }
                    success++
                } catch (e: Exception) {
                    fail++
                }
            }

            withContext(Dispatchers.Main) {
                if (clip.action == ClipboardAction.CUT) {
                    _clipboard.value = null
                }
                _isProcessing.value = false
                refresh()
                onResult(if (clip.action == ClipboardAction.COPY) "Copied $success items" else "Moved $success items")
            }
        }
    }

    fun duplicateFile(file: File, onResult: (String) -> Unit) {
        if (!file.exists()) return
        _isProcessing.value = true
        _processingMessage.value = "Duplicating ${file.name}..."

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val parent = file.parentFile ?: return@launch
                val ext = file.extension
                val nameWithoutExt = file.nameWithoutExtension
                var counter = 1
                var dest: File
                do {
                    val newName = if (ext.isNotEmpty()) "$nameWithoutExt-copy$counter.$ext" else "$nameWithoutExt-copy$counter"
                    dest = File(parent, newName)
                    counter++
                } while (dest.exists())

                if (file.isDirectory) {
                    file.copyRecursively(dest)
                } else {
                    file.copyTo(dest)
                }

                withContext(Dispatchers.Main) {
                    _isProcessing.value = false
                    refresh()
                    onResult("Duplicated as ${dest.name}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isProcessing.value = false
                    onResult("Failed to duplicate: ${e.message}")
                }
            }
        }
    }

    fun renameFile(file: File, newName: String, onResult: (Boolean, String) -> Unit) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            onResult(false, "Name cannot be empty")
            return
        }
        val parent = file.parentFile
        val target = File(parent, trimmed)
        if (target.exists()) {
            onResult(false, "File with this name already exists")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val ok = file.renameTo(target)
            withContext(Dispatchers.Main) {
                if (ok) {
                    refresh()
                    onResult(true, "Renamed successfully")
                } else {
                    onResult(false, "Rename failed")
                }
            }
        }
    }

    fun createNewFolder(name: String, onResult: (Boolean, String) -> Unit) {
        val trimmed = name.trim()
        val dir = _currentDir.value
        if (trimmed.isEmpty() || dir == null) {
            onResult(false, "Invalid folder name")
            return
        }
        val target = File(dir, trimmed)
        if (target.exists()) {
            onResult(false, "Folder already exists")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val ok = target.mkdirs()
            withContext(Dispatchers.Main) {
                if (ok) {
                    refresh()
                    onResult(true, "Folder created")
                } else {
                    onResult(false, "Failed to create folder")
                }
            }
        }
    }

    fun createNewFile(name: String, onResult: (Boolean, String) -> Unit) {
        val trimmed = name.trim()
        val dir = _currentDir.value
        if (trimmed.isEmpty() || dir == null) {
            onResult(false, "Invalid file name")
            return
        }
        val target = File(dir, trimmed)
        if (target.exists()) {
            onResult(false, "File already exists")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val ok = try { target.createNewFile() } catch (e: Exception) { false }
            withContext(Dispatchers.Main) {
                if (ok) {
                    refresh()
                    onResult(true, "File created")
                } else {
                    onResult(false, "Failed to create file")
                }
            }
        }
    }

    fun deleteFiles(filesToDelete: List<File>, onResult: (String) -> Unit) {
        if (filesToDelete.isEmpty()) return
        _isProcessing.value = true
        _processingMessage.value = "Deleting items..."

        viewModelScope.launch(Dispatchers.IO) {
            var success = 0
            var fail = 0
            for (f in filesToDelete) {
                val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
                if (ok) success++ else fail++
            }
            withContext(Dispatchers.Main) {
                _selectedFiles.value = emptySet()
                _isProcessing.value = false
                refresh()
                onResult("Deleted $success items" + if (fail > 0) ", failed $fail" else "")
            }
        }
    }

    fun recordRecent(file: File) {
        _recentFiles.update { list ->
            (listOf(file) + list.filter { it.absolutePath != file.absolutePath }).take(20)
        }
    }

    fun extractZip(zipFile: File, targetDir: File, onResult: (String) -> Unit) {
        _isProcessing.value = true
        _processingMessage.value = "Extracting ${zipFile.name}..."

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

    fun createZip(filesToZip: List<File>, zipName: String, onResult: (String) -> Unit) {
        val dir = _currentDir.value ?: return
        val outFile = File(dir, if (zipName.endsWith(".zip")) zipName else "$zipName.zip")
        _isProcessing.value = true
        _processingMessage.value = "Creating ZIP archive..."

        viewModelScope.launch(Dispatchers.IO) {
            try {
                ZipOutputStream(FileOutputStream(outFile)).use { zos ->
                    for (file in filesToZip) {
                        zipFile(file, file.name, zos)
                    }
                }
                withContext(Dispatchers.Main) {
                    _selectedFiles.value = emptySet()
                    _isProcessing.value = false
                    refresh()
                    onResult("Archive created: ${outFile.name}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isProcessing.value = false
                    onResult("Failed to create ZIP: ${e.message}")
                }
            }
        }
    }

    fun zipSelected(context: Context, safUri: String?, zipName: String, onResult: (String) -> Unit) {
        val filesToZip = _selectedFiles.value.toList()
        if (filesToZip.isEmpty()) return

        _isProcessing.value = true
        _processingMessage.value = "Compressing selection..."

        viewModelScope.launch(Dispatchers.IO) {
            try {
                var outputStream: java.io.OutputStream? = null
                val resolver = context.contentResolver

                if (!safUri.isNullOrEmpty()) {
                    val dir = DocumentFile.fromTreeUri(context, Uri.parse(safUri))
                    if (dir != null) {
                        val newFile = dir.createFile("application/zip", zipName)
                        if (newFile != null) {
                            outputStream = resolver.openOutputStream(newFile.uri)
                        } else throw Exception("Failed to create file in SAF directory")
                    } else throw Exception("Export directory not found or permission denied")
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, zipName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DevVault")
                    }
                    val outUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (outUri != null) {
                        outputStream = resolver.openOutputStream(outUri)
                    } else throw Exception("Failed to create MediaStore entry")
                } else {
                    val dir = _currentDir.value ?: File(context.filesDir, "exports")
                    dir.mkdirs()
                    val outFile = File(dir, zipName)
                    outputStream = FileOutputStream(outFile)
                }

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
                    zipFile(childFile, "$fileName/${childFile.name}", zos)
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
        _processingMessage.value = "Encrypting & moving to Vault..."

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val keystoreHelper = KeystoreHelper()
                val vaultDir = File(context.filesDir, "vault_files")
                if (!vaultDir.exists()) vaultDir.mkdirs()

                var successCount = 0
                for (file in filesToMove) {
                    if (file.isDirectory) continue
                    val destFile = File(vaultDir, UUID.randomUUID().toString())
                    FileInputStream(file).use { fis ->
                        FileOutputStream(destFile).use { fos ->
                            keystoreHelper.encryptStream(fis, fos)
                        }
                    }

                    val (nameIv, nameEncrypted) = keystoreHelper.encrypt(file.name.toByteArray(Charsets.UTF_8))
                    val encryptedNameStr = "${Base64.encodeToString(nameIv, Base64.NO_WRAP)}:${Base64.encodeToString(nameEncrypted, Base64.NO_WRAP)}"

                    vaultViewModel.addVaultItem(
                        VaultItem(
                            encryptedFilename = encryptedNameStr,
                            encryptedPath = destFile.absolutePath,
                            fileSize = file.length(),
                            mimeType = FileUtils.getMimeType(file),
                            timestamp = System.currentTimeMillis()
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

    fun loadStorageAnalytics(rootDir: File) {
        _isAnalyzing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val statFs = android.os.StatFs(rootDir.absolutePath)
                val totalBytes = statFs.totalBytes
                val freeBytes = statFs.availableBytes
                val usedBytes = totalBytes - freeBytes

                var img = 0L
                var vid = 0L
                var aud = 0L
                var doc = 0L
                var arch = 0L
                var code = 0L
                var apk = 0L
                var other = 0L

                val allFiles = mutableListOf<File>()
                val emptyDirs = mutableListOf<File>()
                val sizeMap = mutableMapOf<Long, MutableList<File>>()

                rootDir.walkTopDown().maxDepth(6).forEach { file ->
                    if (file.isDirectory) {
                        val children = file.listFiles()
                        if (children != null && children.isEmpty()) {
                            emptyDirs.add(file)
                        }
                    } else if (file.isFile) {
                        val length = file.length()
                        allFiles.add(file)

                        when (FileUtils.getFileType(file)) {
                            FileType.IMAGE -> img += length
                            FileType.VIDEO -> vid += length
                            FileType.AUDIO -> aud += length
                            FileType.PDF, FileType.DOCUMENT -> doc += length
                            FileType.ARCHIVE -> arch += length
                            FileType.CODE -> code += length
                            FileType.APK -> apk += length
                            else -> other += length
                        }

                        if (length > 1024 * 1024) { // Only track duplicates > 1MB
                            sizeMap.getOrPut(length) { mutableListOf() }.add(file)
                        }
                    }
                }

                val largest = allFiles.sortedByDescending { it.length() }.take(30)

                // Detect duplicates based on identical size and hash
                val dupGroups = mutableListOf<List<File>>()
                for ((_, list) in sizeMap) {
                    if (list.size > 1) {
                        val hashMap = mutableMapOf<String, MutableList<File>>()
                        for (f in list) {
                            val hash = FileUtils.computeMD5(f)
                            if (hash.isNotEmpty() && hash != "Unavailable") {
                                hashMap.getOrPut(hash) { mutableListOf() }.add(f)
                            }
                        }
                        for ((_, hashGroup) in hashMap) {
                            if (hashGroup.size > 1) {
                                dupGroups.add(hashGroup)
                            }
                        }
                    }
                }

                val analyticsData = StorageAnalytics(
                    totalBytes = totalBytes,
                    usedBytes = usedBytes,
                    freeBytes = freeBytes,
                    imageBytes = img,
                    videoBytes = vid,
                    audioBytes = aud,
                    documentBytes = doc,
                    archiveBytes = arch,
                    codeBytes = code,
                    apkBytes = apk,
                    otherBytes = other,
                    largestFiles = largest,
                    emptyFolders = emptyDirs.take(30),
                    duplicateGroups = dupGroups.take(20)
                )

                withContext(Dispatchers.Main) {
                    _analytics.value = analyticsData
                    _isAnalyzing.value = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isAnalyzing.value = false
                }
            }
        }
    }
}
