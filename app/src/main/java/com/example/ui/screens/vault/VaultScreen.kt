package com.example.ui.screens.vault

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.data.UserPreferencesRepository
import com.example.security.KeystoreHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import android.util.Base64
import com.example.data.VaultItem

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
    
    var itemToExport by remember { mutableStateOf<VaultItem?>(null) }
    
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null && itemToExport != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val file = File(itemToExport!!.encryptedPath)
                    val fileBytes = file.readBytes()
                    val iv = fileBytes.copyOfRange(0, 12)
                    val encryptedData = fileBytes.copyOfRange(12, fileBytes.size)
                    val decryptedBytes = KeystoreHelper().decrypt(iv, encryptedData)
                    
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(decryptedBytes)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Exported successfully", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
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
                .setSubtitle("Use your biometric to unlock")
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

    var showChangePinDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vault") },
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
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            if (!hasPin) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Lock, contentDescription = "Setup", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Setup a PIN for your Vault")
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
                        Text(errorMsg, color = MaterialTheme.colorScheme.error)
                    }
                }
            } else if (!isUnlocked) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Lock, contentDescription = "Locked", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
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
                        Button(onClick = { showBiometricPrompt() }) {
                            Text("Use Biometrics")
                        }
                    }
                }
            } else {
                if (vaultItems.isEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.LockOpen, contentDescription = "Unlocked", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Vault is unlocked", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No items in Vault.")
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(vaultItems) { item ->
                            val decryptedName = try {
                                val split = item.encryptedFilename.split(":")
                                val iv = Base64.decode(split[0], Base64.DEFAULT)
                                val data = Base64.decode(split[1], Base64.DEFAULT)
                                String(KeystoreHelper().decrypt(iv, data))
                            } catch (e: Exception) { "Unknown/Corrupted" }
                            
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(decryptedName, style = MaterialTheme.typography.titleMedium)
                                        Text("Added: ${java.text.SimpleDateFormat("MMM dd, yyyy").format(java.util.Date(item.timestamp))}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    var menuExpanded by remember { mutableStateOf(false) }
                                    Box {
                                        IconButton(onClick = { menuExpanded = true }) {
                                            Icon(Icons.Default.MoreVert, contentDescription = "Item Options")
                                        }
                                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                            DropdownMenuItem(
                                                text = { Text("Open") },
                                                onClick = {
                                                    menuExpanded = false
                                                    coroutineScope.launch(Dispatchers.IO) {
                                                        try {
                                                            val file = File(item.encryptedPath)
                                                            val fileBytes = file.readBytes()
                                                            val iv = fileBytes.copyOfRange(0, 12)
                                                            val encryptedData = fileBytes.copyOfRange(12, fileBytes.size)
                                                            val decryptedBytes = KeystoreHelper().decrypt(iv, encryptedData)
                                                            
                                                            val cacheDir = File(context.cacheDir, "vault_temp")
                                                            if (!cacheDir.exists()) cacheDir.mkdirs()
                                                            val tempFile = File(cacheDir, decryptedName)
                                                            tempFile.writeBytes(decryptedBytes)
                                                            
                                                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)
                                                            
                                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                                setDataAndType(uri, "*/*")
                                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                            }
                                                            withContext(Dispatchers.Main) {
                                                                context.startActivity(Intent.createChooser(intent, "Open File"))
                                                            }
                                                        } catch (e: Exception) {
                                                            withContext(Dispatchers.Main) {
                                                                Toast.makeText(context, "Failed to open: ${e.message}", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    }
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Export") },
                                                onClick = {
                                                    menuExpanded = false
                                                    itemToExport = item
                                                    exportLauncher.launch(decryptedName)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Delete") },
                                                onClick = {
                                                    menuExpanded = false
                                                    viewModel.deleteVaultItem(item)
                                                    File(item.encryptedPath).delete()
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
                        OutlinedTextField(value = newPin, onValueChange = { newPin = it }, label = { Text("New PIN") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
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
