package com.example.ui.screens.contacts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import com.example.data.AppDatabase
import com.example.data.ContactBackup
import com.example.security.KeystoreHelper
import android.util.Base64
import org.json.JSONObject

data class ContactItem(val id: String, val name: String, val phoneNumber: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var hasPermissions by remember { mutableStateOf(false) }
    var showRationale by remember { mutableStateOf(false) }
    var showBackups by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions: Map<String, Boolean> ->
        hasPermissions = permissions[Manifest.permission.READ_CONTACTS] == true && 
                         permissions[Manifest.permission.WRITE_CONTACTS] == true
        if (!hasPermissions) {
            showRationale = true
        }
    }

    LaunchedEffect(Unit) {
        val readGranted = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val writeGranted = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        hasPermissions = readGranted && writeGranted
        if (!hasPermissions) {
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showBackups) "Contact Backups" else "Contacts") },
                actions = {
                    TextButton(onClick = { showBackups = !showBackups }) {
                        Text(if (showBackups) "Live Contacts" else "View Backups")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (hasPermissions) {
                if (showBackups) {
                    ContactBackupsList(context)
                } else {
                    ContactsList(context)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        if (showRationale) "The contacts permissions are important for this app to display and manage your contacts. Please grant them in settings." 
                        else "Contacts permissions required for this feature to be available.",
                        modifier = Modifier.padding(16.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { 
                        if (showRationale) {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        } else {
                            permissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))
                        }
                    }) {
                        Text(if (showRationale) "Open Settings" else "Request permissions")
                    }
                }
            }
        }
    }
}

@Composable
fun ContactsList(context: Context) {
    var contacts by remember { mutableStateOf<List<ContactItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    
    var contactToDelete by remember { mutableStateOf<ContactItem?>(null) }
    var actionResult by remember { mutableStateOf<String?>(null) }

    fun loadContacts() {
        scope.launch(Dispatchers.IO) {
            val contactList = mutableListOf<ContactItem>()
            try {
                val cursor = context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    null, null, null, null
                )
                
                cursor?.use {
                    val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    
                    while (it.moveToNext()) {
                        if (idIndex >= 0 && nameIndex >= 0 && numberIndex >= 0) {
                            val id = it.getString(idIndex)
                            val name = it.getString(nameIndex)
                            val number = it.getString(numberIndex)
                            contactList.add(ContactItem(id, name, number))
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore exception, just show empty or partial list
            }
            withContext(Dispatchers.Main) {
                contacts = contactList.distinctBy { it.id }.sortedBy { it.name }
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadContacts()
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${contacts.size} Contacts found", style = MaterialTheme.typography.titleMedium)
            }
            
            if (actionResult != null) {
                Text(actionResult!!, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp))
                LaunchedEffect(actionResult) {
                    kotlinx.coroutines.delay(3000)
                    actionResult = null
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(contacts) { contact ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(contact.name, style = MaterialTheme.typography.titleMedium)
                                    Text(contact.phoneNumber, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            IconButton(onClick = { contactToDelete = contact }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete and Backup", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
        
        if (contactToDelete != null) {
            AlertDialog(
                onDismissRequest = { contactToDelete = null },
                title = { Text("Delete Contact") },
                text = { Text("Are you sure you want to delete ${contactToDelete!!.name}? It will be backed up to the Vault securely before deletion.") },
                confirmButton = {
                    TextButton(onClick = {
                        val c = contactToDelete!!
                        contactToDelete = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                try {
                                    // 1. Encrypt Data
                                    val json = JSONObject().apply {
                                        put("name", c.name)
                                        put("phoneNumber", c.phoneNumber)
                                    }.toString()
                                    val keystore = KeystoreHelper()
                                    val (iv, encrypted) = keystore.encrypt(json.toByteArray())
                                    val encryptedString = Base64.encodeToString(iv, Base64.DEFAULT) + ":" + Base64.encodeToString(encrypted, Base64.DEFAULT)
                                    
                                    // 2. Insert to Room
                                    val db = AppDatabase.getDatabase(context)
                                    db.contactBackupDao().insert(ContactBackup(name = "Encrypted", phoneNumber = "Encrypted", encryptedData = encryptedString))
                                    
                                    // 3. Delete from Contacts
                                    val uri = ContactsContract.RawContacts.CONTENT_URI
                                    val where = "${ContactsContract.RawContacts.CONTACT_ID} = ?"
                                    val args = arrayOf(c.id)
                                    context.contentResolver.delete(uri, where, args)
                                    
                                    actionResult = "Backed up and deleted successfully"
                                } catch (e: Exception) {
                                    actionResult = "Failed: ${e.message}"
                                }
                            }
                            loadContacts()
                        }
                    }) { Text("Delete & Backup", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { contactToDelete = null }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
fun ContactBackupsList(context: Context) {
    val db = AppDatabase.getDatabase(context)
    val backups by db.contactBackupDao().getAllBackups().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var backupToDelete by remember { mutableStateOf<ContactBackup?>(null) }
    
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Contacts deleted through this app are reliably preserved. Contacts deleted through any other app (Google Contacts, dialer, etc.) are not backed up by DevVault.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(backups) { backup ->
                var name = "Unknown"
                var number = "Unknown"
                if (backup.encryptedData != null) {
                    try {
                        val parts = backup.encryptedData.split(":")
                        val iv = Base64.decode(parts[0], Base64.DEFAULT)
                        val enc = Base64.decode(parts[1], Base64.DEFAULT)
                        val decrypted = String(KeystoreHelper().decrypt(iv, enc))
                        val json = JSONObject(decrypted)
                        name = json.getString("name")
                        number = json.getString("phoneNumber")
                    } catch (e: Exception) {
                        name = "Corrupted Backup"
                    }
                }
                
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(name, style = MaterialTheme.typography.titleMedium)
                                Text(number, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row {
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val ops = ArrayList<ContentProviderOperation>()
                                        val rawContactInsertIndex = ops.size
                                        
                                        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                                            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                                            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                                            .build())
                                            
                                        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                                            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                                            .build())
                                            
                                        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                                            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                                            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                                            .build())
                                            
                                        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                                        db.contactBackupDao().delete(backup)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "Contact restored", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "Restore failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Restore, contentDescription = "Restore")
                            }
                            IconButton(onClick = { backupToDelete = backup }) {
                                Icon(Icons.Default.Delete, contentDescription = "Permanently Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
    
    if (backupToDelete != null) {
        AlertDialog(
            onDismissRequest = { backupToDelete = null },
            title = { Text("Permanently Delete Backup") },
            text = { Text("Are you sure you want to permanently delete this backup? It cannot be recovered.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        db.contactBackupDao().delete(backupToDelete!!)
                        backupToDelete = null
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { backupToDelete = null }) { Text("Cancel") }
            }
        )
    }
}
