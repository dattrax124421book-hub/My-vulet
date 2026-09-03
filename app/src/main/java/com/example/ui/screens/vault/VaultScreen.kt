package com.example.ui.screens.vault

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AppDatabase
import com.example.data.UserPreferencesRepository
import com.example.data.VaultItem
import com.example.security.KeystoreHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(onBack: () -> Unit, onNavigateToCodeEditor: (String) -> Unit = {}) {
    val viewModel: VaultViewModel = viewModel()
    val isUnlocked by viewModel.isUnlocked.collectAsState()
    val hasPin by viewModel.hasPin.collectAsState()
    val vaultItems by viewModel.vaultItems.collectAsState()
    
    var pinInput by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }

    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    var biometricEnabled by remember { mutableStateOf(true) }
    
    var isProcessing by remember { mutableStateOf(false) }
    var processingMessage by remember { mutableStateOf("") }

    var itemToExport by remember { mutableStateOf<VaultItem?>(null) }
    var exportProposedName by remember { mutableStateOf("") }
    var itemToDelete by remember { mutableStateOf<VaultItem?>(null) }

    // Stream-based bulk/single file encryption without in-memory buffering
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            isProcessing = true
            processingMessage = "Encrypting file(s) into secure vault..."
            coroutineScope.launch(Dispatchers.IO) {
                var addedCount = 0
                val db = AppDatabase.getDatabase(context)
                val vaultDir = File(context.filesDir, "vault_files")
                if (!vaultDir.exists()) {
                    vaultDir.mkdirs()
                    File(vaultDir, ".nomedia").createNewFile()
                }
                val helper = KeystoreHelper()

                for (uri in uris) {
                    try {
                        val docFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
                        val originalName = docFile?.name ?: "vault_file_${System.currentTimeMillis()}"
                        val fileSize = docFile?.length() ?: 0L
                        val mimeType = context.contentResolver.getType(uri) ?: "*/*"

                        val (iv, encryptedNameData) = helper.encrypt(originalName.toByteArray(Charsets.UTF_8))
                        val encryptedFilename = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encryptedNameData, Base64.NO_WRAP)

                        val newFile = File(vaultDir, java.util.UUID.randomUUID().toString())
                        context.contentResolver.openInputStream(uri)?.use { fis ->
                            FileOutputStream(newFile).use { fos ->
                                helper.encryptStream(fis, fos)
                            }
                        }

                        db.vaultItemDao().insert(VaultItem(
                            encryptedPath = newFile.absolutePath,
                            encryptedFilename = encryptedFilename,
                            timestamp = System.currentTimeMillis(),
                            fileSize = if (fileSize > 0) fileSize else newFile.length(),
                            mimeType = mimeType,
                            originalPath = uri.toString()
                        ))
                        addedCount++
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                withContext(Dispatchers.Main) {
                    isProcessing = false
                    if (addedCount > 0) {
                        Toast.makeText(context, "Encrypted and added $addedCount item(s)", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Failed to import selected files", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    
    // Stream-based file export without in-memory buffering
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null && itemToExport != null) {
            val targetItem = itemToExport!!
            isProcessing = true
            processingMessage = "Decrypting and exporting file..."
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val file = File(targetItem.encryptedPath)
                    FileInputStream(file).use { fis ->
                        context.contentResolver.openOutputStream(uri)?.use { fos ->
                            KeystoreHelper().decryptStream(fis, fos)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        isProcessing = false
                        Toast.makeText(context, "Exported successfully", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isProcessing = false
                        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                itemToExport = null
            }
        }
    }

    LaunchedEffect(Unit) {
        val repo = UserPreferencesRepository(context)
        val prefs = repo.userPreferencesFlow.first()
        biometricEnabled = prefs.biometricUnlock
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkForceLock()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun showBiometricPrompt() {
        if (activity == null || !biometricEnabled) return
        val biometricManager = BiometricManager.from(context)
        if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS) {
            val executor = ContextCompat.getMainExecutor(context)
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Vault")
                .setSubtitle("Use your biometric credential to unlock")
                .setNegativeButtonText("Use PIN")
                .build()

            val biometricPrompt = BiometricPrompt(activity, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        viewModel.unlockWithBiometrics()
                    }
                })
            biometricPrompt.authenticate(promptInfo)
        }
    }

    LaunchedEffect(hasPin, isUnlocked, biometricEnabled) {
        if (hasPin && !isUnlocked && biometricEnabled) {
            showBiometricPrompt()
        }
    }

    // Cache decrypted filenames so scrolling is ultra fast without Keystore crypto lag
    val decryptedFilenames = remember(vaultItems) {
        val helper = KeystoreHelper()
        vaultItems.associate { item ->
            val name = try {
                val split = item.encryptedFilename.split(":")
                val iv = Base64.decode(split[0], Base64.DEFAULT)
                val data = Base64.decode(split[1], Base64.DEFAULT)
                String(helper.decrypt(iv, data), Charsets.UTF_8)
            } catch (e: Exception) {
                "Item #${item.id}"
            }
            item.id to name
        }
    }

    var showChangePinDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Secure Vault") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isUnlocked) {
                        var expanded by remember { mutableStateOf(false) }
                        IconButton(onClick = { expanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(text = { Text("Lock Vault") }, onClick = { expanded = false; viewModel.lock() })
                            DropdownMenuItem(text = { Text("Change PIN") }, onClick = { expanded = false; showChangePinDialog = true })
                            DropdownMenuItem(text = { Text("Reset Vault") }, onClick = { expanded = false; showResetDialog = true })
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (isUnlocked) {
                FloatingActionButton(onClick = {
                    filePickerLauncher.launch(arrayOf("*/*"))
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Files")
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            if (!hasPin) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(Icons.Default.Lock, contentDescription = "Setup", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Setup a PIN for your Vault", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Choose at least 4 digits to secure your files.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { pinInput = it },
                        label = { Text("New PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { 
                        if (pinInput.length >= 4) {
                            viewModel.setPin(pinInput)
                            pinInput = ""
                        } else {
                            errorMsg = "PIN must be at least 4 digits"
                        }
                    }) {
                        Text("Set PIN")
                    }
                    if (errorMsg.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(errorMsg, color = MaterialTheme.colorScheme.error)
                    }
                }
            } else if (!isUnlocked) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(Icons.Default.Lock, contentDescription = "Locked", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Vault is Locked", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { 
                            pinInput = it
                            if (viewModel.unlock(it)) {
                                pinInput = ""
                                errorMsg = ""
                            }
                        },
                        label = { Text("Enter PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    if (biometricEnabled) {
                        OutlinedButton(onClick = { showBiometricPrompt() }) {
                            Icon(Icons.Default.Fingerprint, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Use Biometrics")
                        }
                    }
                }
            } else {
                if (vaultItems.isEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(Icons.Default.FolderSpecial, contentDescription = "Empty", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Secure Vault is Empty", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Tap the + button to encrypt and safely store confidential documents, codes, and media.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 80.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(vaultItems, key = { it.id }) { item ->
                            val decryptedName = decryptedFilenames[item.id] ?: "Item #${item.id}"
                            
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp)),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // File type icon
                                    val ext = decryptedName.substringAfterLast('.', "").lowercase()
                                    val icon = when (ext) {
                                        "jpg", "jpeg", "png", "gif", "webp" -> Icons.Default.Image
                                        "mp4", "mkv", "avi", "mov" -> Icons.Default.Movie
                                        "mp3", "wav", "m4a", "ogg" -> Icons.Default.Audiotrack
                                        "zip", "tar", "gz", "7z", "rar" -> Icons.Default.FolderZip
                                        "kt", "java", "py", "js", "ts", "json", "xml", "html", "css", "cpp", "c", "h" -> Icons.Default.Code
                                        "pdf" -> Icons.Default.PictureAsPdf
                                        else -> Icons.Default.InsertDriveFile
                                    }

                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(36.dp)
                                    )

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = decryptedName,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        val formattedSize = Formatter.formatFileSize(context, if (item.fileSize > 0) item.fileSize else File(item.encryptedPath).length())
                                        val dateStr = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault()).format(Date(item.timestamp))
                                        Text(
                                            text = "$formattedSize • $dateStr",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    var menuExpanded by remember { mutableStateOf(false) }
                                    Box {
                                        IconButton(onClick = { menuExpanded = true }) {
                                            Icon(Icons.Default.MoreVert, contentDescription = "Item Options")
                                        }
                                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                            DropdownMenuItem(
                                                leadingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null) },
                                                text = { Text("Open File") },
                                                onClick = {
                                                    menuExpanded = false
                                                    isProcessing = true
                                                    processingMessage = "Decrypting file to open..."
                                                    coroutineScope.launch(Dispatchers.IO) {
                                                        try {
                                                            val file = File(item.encryptedPath)
                                                            val tempDir = File(context.cacheDir, "vault_temp")
                                                            if (!tempDir.exists()) tempDir.mkdirs()
                                                            val tempFile = File(tempDir, decryptedName)
                                                            FileInputStream(file).use { fis ->
                                                                FileOutputStream(tempFile).use { fos ->
                                                                    KeystoreHelper().decryptStream(fis, fos)
                                                                }
                                                            }
                                                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)
                                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                                setDataAndType(uri, if (item.mimeType != "*/*") item.mimeType else "*/*")
                                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                            }
                                                            withContext(Dispatchers.Main) {
                                                                isProcessing = false
                                                                try {
                                                                    context.startActivity(Intent.createChooser(intent, "Open File"))
                                                                } catch (e: Exception) {
                                                                    Toast.makeText(context, "No app available to open this file format", Toast.LENGTH_SHORT).show()
                                                                }
                                                            }
                                                        } catch (e: Exception) {
                                                            withContext(Dispatchers.Main) {
                                                                isProcessing = false
                                                                Toast.makeText(context, "Failed to open: ${e.message}", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    }
                                                }
                                            )
                                            // If code or text, allow opening in Code Editor
                                            val isCodeOrText = ext in listOf("kt", "java", "py", "js", "ts", "json", "xml", "html", "css", "cpp", "c", "h", "txt", "md", "log", "sh")
                                            if (isCodeOrText) {
                                                DropdownMenuItem(
                                                    leadingIcon = { Icon(Icons.Default.Code, contentDescription = null) },
                                                    text = { Text("Open in Editor") },
                                                    onClick = {
                                                        menuExpanded = false
                                                        isProcessing = true
                                                        processingMessage = "Opening in code editor..."
                                                        coroutineScope.launch(Dispatchers.IO) {
                                                            try {
                                                                val file = File(item.encryptedPath)
                                                                val tempDir = File(context.cacheDir, "vault_temp")
                                                                if (!tempDir.exists()) tempDir.mkdirs()
                                                                val tempFile = File(tempDir, decryptedName)
                                                                FileInputStream(file).use { fis ->
                                                                    FileOutputStream(tempFile).use { fos ->
                                                                        KeystoreHelper().decryptStream(fis, fos)
                                                                    }
                                                                }
                                                                withContext(Dispatchers.Main) {
                                                                    isProcessing = false
                                                                    onNavigateToCodeEditor(tempFile.absolutePath)
                                                                }
                                                            } catch (e: Exception) {
                                                                withContext(Dispatchers.Main) {
                                                                    isProcessing = false
                                                                    Toast.makeText(context, "Failed to decrypt for editor: ${e.message}", Toast.LENGTH_SHORT).show()
                                                                }
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                            DropdownMenuItem(
                                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                                text = { Text("Export") },
                                                onClick = {
                                                    menuExpanded = false
                                                    itemToExport = item
                                                    exportProposedName = decryptedName
                                                    exportLauncher.launch(decryptedName)
                                                }
                                            )
                                            DropdownMenuItem(
                                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                                onClick = {
                                                    menuExpanded = false
                                                    itemToDelete = item
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Processing indicator dialog
        if (isProcessing) {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = { Text("Processing Vault Data") },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                        Text(processingMessage, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            )
        }

        // Delete item dialog
        if (itemToDelete != null) {
            val item = itemToDelete!!
            val name = decryptedFilenames[item.id] ?: "this file"
            AlertDialog(
                onDismissRequest = { itemToDelete = null },
                title = { Text("Delete Vault Item") },
                text = { Text("Are you sure you want to permanently delete '$name' from the vault? The encrypted file on storage will also be erased.") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.deleteVaultItem(item)
                        try {
                            File(item.encryptedPath).delete()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        itemToDelete = null
                        Toast.makeText(context, "Item deleted", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { itemToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showChangePinDialog) {
            var oldPin by remember { mutableStateOf("") }
            var newPin by remember { mutableStateOf("") }
            var error by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showChangePinDialog = false },
                title = { Text("Change PIN") },
                text = {
                    Column {
                        OutlinedTextField(value = oldPin, onValueChange = { oldPin = it }, label = { Text("Old PIN") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = newPin, onValueChange = { newPin = it }, label = { Text("New PIN") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                        if (error.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(error, color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (newPin.length < 4) error = "New PIN must be >= 4 digits"
                        else if (viewModel.changePin(oldPin, newPin)) showChangePinDialog = false
                        else error = "Incorrect Old PIN"
                    }) { Text("Change") }
                },
                dismissButton = { TextButton(onClick = { showChangePinDialog = false }) { Text("Cancel") } }
            )
        }
        
        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp)); Text("Reset Vault") } },
                text = { Text("This will PERMANENTLY DELETE all vault settings and encrypted records in the database. Files on disk will become orphaned and unrecoverable. This action cannot be undone.") },
                confirmButton = {
                    Button(onClick = { viewModel.resetVault(); showResetDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("RESET PERMANENTLY") }
                },
                dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("Cancel") } }
            )
        }
    }
}

