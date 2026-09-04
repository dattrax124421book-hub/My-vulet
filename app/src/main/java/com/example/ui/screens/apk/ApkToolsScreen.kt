package com.example.ui.screens.apk

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.documentfile.provider.DocumentFile
import com.example.data.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

data class ApkItem(
    val name: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val icon: android.graphics.drawable.Drawable,
    val baseSourceDir: String,
    val splitSourceDirs: List<String>?,
    val isSystem: Boolean,
    val totalSize: Long,
    val minSdk: Int = 0,
    val targetSdk: Int = 0
)

data class ApkDeepDetails(
    val permissions: List<String>,
    val activitiesCount: Int,
    val servicesCount: Int,
    val receiversCount: Int,
    val providersCount: Int,
    val signatureSha256: String,
    val signatureMd5: String,
    val nativeLibs: List<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkToolsScreen(
    onBack: () -> Unit,
    onNavigateToHex: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    val scope = rememberCoroutineScope()
    val prefsRepo = remember { UserPreferencesRepository(context) }

    var apps by remember { mutableStateOf<List<ApkItem>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var showSystemApps by remember { mutableStateOf(false) }

    var extractingApp by remember { mutableStateOf<String?>(null) }
    var extractingProgress by remember { mutableStateOf<Float?>(null) }

    // Deep inspect sheet state
    var inspectingApp by remember { mutableStateOf<ApkItem?>(null) }
    var inspectDetails by remember { mutableStateOf<ApkDeepDetails?>(null) }
    var isInspectingLoading by remember { mutableStateOf(false) }

    val loadApps = {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            val mapped = installedApps.mapNotNull { appInfo ->
                if (!showSystemApps && (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) return@mapNotNull null

                try {
                    val packageInfo = packageManager.getPackageInfo(appInfo.packageName, 0)
                    val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
                    val versionName = packageInfo.versionName ?: "N/A"

                    val baseFile = File(appInfo.sourceDir)
                    var totalSize = baseFile.length()
                    val splits = appInfo.splitSourceDirs?.toList()
                    splits?.forEach { splitPath ->
                        totalSize += File(splitPath).length()
                    }

                    val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) appInfo.minSdkVersion else 0
                    val targetSdk = appInfo.targetSdkVersion

                    ApkItem(
                        name = packageManager.getApplicationLabel(appInfo).toString(),
                        packageName = appInfo.packageName,
                        versionName = versionName,
                        versionCode = versionCode,
                        icon = packageManager.getApplicationIcon(appInfo),
                        baseSourceDir = appInfo.sourceDir,
                        splitSourceDirs = splits,
                        isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        totalSize = totalSize,
                        minSdk = minSdk,
                        targetSdk = targetSdk
                    )
                } catch (e: Exception) {
                    null
                }
            }.sortedBy { it.name.lowercase() }

            withContext(Dispatchers.Main) {
                apps = mapped
                isLoading = false
            }
        }
    }

    LaunchedEffect(showSystemApps) {
        loadApps()
    }

    var pendingExtraction by remember { mutableStateOf<ApkItem?>(null) }

    val safPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            scope.launch {
                prefsRepo.updateDefaultExportUri(uri.toString())
                pendingExtraction?.let { app ->
                    extractApk(context, app, uri.toString(), scope, { extractingApp = it }, { extractingProgress = it })
                }
                pendingExtraction = null
            }
        } else {
            pendingExtraction = null
            Toast.makeText(context, "Export cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    fun loadDeepDetails(app: ApkItem) {
        inspectingApp = app
        isInspectingLoading = true
        inspectDetails = null

        scope.launch(Dispatchers.IO) {
            try {
                val flags = PackageManager.GET_PERMISSIONS or
                        PackageManager.GET_ACTIVITIES or
                        PackageManager.GET_SERVICES or
                        PackageManager.GET_RECEIVERS or
                        PackageManager.GET_PROVIDERS or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

                val pi = packageManager.getPackageInfo(app.packageName, flags)
                val perms = pi.requestedPermissions?.toList() ?: emptyList()
                val actCount = pi.activities?.size ?: 0
                val srvCount = pi.services?.size ?: 0
                val recCount = pi.receivers?.size ?: 0
                val prvCount = pi.providers?.size ?: 0

                var sha256Str = "N/A"
                var md5Str = "N/A"

                val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pi.signingInfo?.apkContentsSigners ?: pi.signingInfo?.signingCertificateHistory
                } else {
                    @Suppress("DEPRECATION")
                    pi.signatures
                }

                if (!sigs.isNullOrEmpty()) {
                    val certBytes = sigs[0].toByteArray()
                    val md5 = MessageDigest.getInstance("MD5").digest(certBytes)
                    val sha256 = MessageDigest.getInstance("SHA-256").digest(certBytes)
                    md5Str = md5.joinToString(":") { "%02X".format(it) }
                    sha256Str = sha256.joinToString(":") { "%02X".format(it) }
                }

                // Check native libs inside base APK
                val nativeLibs = mutableListOf<String>()
                try {
                    ZipFile(File(app.baseSourceDir)).use { zip ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (entry.name.startsWith("lib/") && entry.name.endsWith(".so")) {
                                val arch = entry.name.removePrefix("lib/").substringBefore("/")
                                if (!nativeLibs.contains(arch)) {
                                    nativeLibs.add(arch)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // ignore
                }

                withContext(Dispatchers.Main) {
                    inspectDetails = ApkDeepDetails(
                        permissions = perms,
                        activitiesCount = actCount,
                        servicesCount = srvCount,
                        receiversCount = recCount,
                        providersCount = prvCount,
                        signatureSha256 = sha256Str,
                        signatureMd5 = md5Str,
                        nativeLibs = nativeLibs
                    )
                    isInspectingLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isInspectingLoading = false
                    Toast.makeText(context, "Inspection error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val filteredApps = if (searchQuery.isBlank()) apps else apps.filter {
        it.name.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pro APK Inspector & Extractor") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("System", style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = showSystemApps,
                            onCheckedChange = { showSystemApps = it },
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search installed packages or apps...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredApps) { app ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { loadDeepDetails(app) }
                        ) {
                            Column(modifier = Modifier.padding(14.dp).fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Image(
                                        bitmap = app.icon.toBitmap().asImageBitmap(),
                                        contentDescription = app.name,
                                        modifier = Modifier.size(46.dp)
                                    )
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(app.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                                        Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Row(
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                                        ) {
                                            Text("v${app.versionName} (${app.versionCode})", style = MaterialTheme.typography.labelSmall)
                                            Text(Formatter.formatShortFileSize(context, app.totalSize), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }

                                if (!app.splitSourceDirs.isNullOrEmpty()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text("⚡ Contains ${app.splitSourceDirs.size + 1} APK splits (App Bundle)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = { loadDeepDetails(app) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Inspect", style = MaterialTheme.typography.labelSmall)
                                    }

                                    Spacer(Modifier.width(8.dp))

                                    Button(
                                        onClick = {
                                            scope.launch {
                                                val prefs = prefsRepo.userPreferencesFlow.first()
                                                val exportUri = prefs.defaultExportUri
                                                if (exportUri.isNotEmpty()) {
                                                    extractApk(context, app, exportUri, scope, { extractingApp = it }, { extractingProgress = it })
                                                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                    extractApk(context, app, null, scope, { extractingApp = it }, { extractingProgress = it })
                                                } else {
                                                    pendingExtraction = app
                                                    safPickerLauncher.launch(null)
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Extract", style = MaterialTheme.typography.labelSmall)
                                    }
                                }

                                if (extractingApp == app.packageName) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    val p = extractingProgress
                                    if (p != null) {
                                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                                    } else {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Deep Inspection Bottom Sheet
    if (inspectingApp != null) {
        val targetApp = inspectingApp!!
        ModalBottomSheet(
            onDismissRequest = { inspectingApp = null }
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            bitmap = targetApp.icon.toBitmap().asImageBitmap(),
                            contentDescription = targetApp.name,
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(targetApp.name, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                            Text(targetApp.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            Text("v${targetApp.versionName} • Build ${targetApp.versionCode}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                // Quick Action Bar
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                val intent = context.packageManager.getLaunchIntentForPackage(targetApp.packageName)
                                if (intent != null) context.startActivity(intent)
                                else Toast.makeText(context, "No launcher activity", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Launch, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Launch")
                        }

                        FilledTonalButton(
                            onClick = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", targetApp.packageName, null)
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("App Info")
                        }

                        FilledTonalButton(
                            onClick = {
                                onNavigateToHex(targetApp.baseSourceDir)
                                inspectingApp = null
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.DataArray, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Hex View")
                        }
                    }
                }

                item {
                    HorizontalDivider()
                }

                // Architecture & SDK details
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Technical SDK & Architecture", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Target SDK:", style = MaterialTheme.typography.bodySmall)
                                Text("Android ${targetApp.targetSdk}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Min SDK:", style = MaterialTheme.typography.bodySmall)
                                Text("Android ${targetApp.minSdk}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Base APK Path:", style = MaterialTheme.typography.bodySmall)
                                Text(targetApp.baseSourceDir, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), maxLines = 1)
                            }
                        }
                    }
                }

                if (isInspectingLoading) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (inspectDetails != null) {
                    val d = inspectDetails!!

                    // Components count
                    item {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Declared Manifest Components", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                Text("• Activities: ${d.activitiesCount}", style = MaterialTheme.typography.bodySmall)
                                Text("• Services: ${d.servicesCount}", style = MaterialTheme.typography.bodySmall)
                                Text("• Broadcast Receivers: ${d.receiversCount}", style = MaterialTheme.typography.bodySmall)
                                Text("• Content Providers: ${d.providersCount}", style = MaterialTheme.typography.bodySmall)
                                if (d.nativeLibs.isNotEmpty()) {
                                    Text("• Native SO Architectures: ${d.nativeLibs.joinToString(", ")}", style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.primary))
                                }
                            }
                        }
                    }

                    // Certificate Signatures
                    item {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("APK Signing Certificate Fingerprint", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                Text("SHA-256:", style = MaterialTheme.typography.labelSmall)
                                Text(
                                    d.signatureSha256,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                                        .padding(6.dp)
                                        .clickable {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("SHA256", d.signatureSha256))
                                            Toast.makeText(context, "SHA-256 copied", Toast.LENGTH_SHORT).show()
                                        }
                                )

                                Text("MD5:", style = MaterialTheme.typography.labelSmall)
                                Text(
                                    d.signatureMd5,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                                        .padding(6.dp)
                                        .clickable {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("MD5", d.signatureMd5))
                                            Toast.makeText(context, "MD5 copied", Toast.LENGTH_SHORT).show()
                                        }
                                )
                            }
                        }
                    }

                    // Permissions
                    item {
                        Text(
                            "Requested Permissions (${d.permissions.size})",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    items(d.permissions) { perm ->
                        val isDangerous = perm.contains("CAMERA") || perm.contains("LOCATION") || perm.contains("STORAGE") || perm.contains("CONTACTS") || perm.contains("RECORD_AUDIO") || perm.contains("SMS")
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isDangerous) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = perm.substringAfterLast("."),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isDangerous) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isDangerous) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                ),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

private suspend fun extractApk(
    context: Context,
    app: ApkItem,
    safUri: String?,
    scope: kotlinx.coroutines.CoroutineScope,
    setExtracting: (String?) -> Unit,
    setProgress: (Float?) -> Unit
) {
    setExtracting(app.packageName)
    setProgress(0f)

    withContext(Dispatchers.IO) {
        try {
            val filesToExtract = mutableListOf<File>()
            filesToExtract.add(File(app.baseSourceDir))
            app.splitSourceDirs?.forEach { filesToExtract.add(File(it)) }

            var totalBytesToCopy = app.totalSize
            var bytesCopied = 0L

            val savedUris = mutableListOf<Uri>()
            val resolver = context.contentResolver

            for (file in filesToExtract) {
                val isBase = file.absolutePath == app.baseSourceDir
                val suffix = if (isBase) "base" else file.name
                val fileName = "${app.packageName}_${app.versionCode}_$suffix.apk"

                var outUri: Uri? = null
                var outputStream: java.io.OutputStream? = null

                if (!safUri.isNullOrEmpty()) {
                    val dir = DocumentFile.fromTreeUri(context, Uri.parse(safUri))
                    if (dir != null) {
                        val existing = dir.findFile(fileName)
                        existing?.delete()
                        val newFile = dir.createFile("application/vnd.android.package-archive", fileName)
                        if (newFile != null) {
                            outUri = newFile.uri
                            outputStream = resolver.openOutputStream(outUri)
                        } else {
                            throw Exception("Failed to create file in SAF directory")
                        }
                    } else {
                        throw Exception("Export directory not found or permission denied")
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DevVault")
                    }
                    outUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (outUri != null) {
                        outputStream = resolver.openOutputStream(outUri)
                    } else {
                        throw Exception("Failed to create MediaStore entry")
                    }
                } else {
                    throw Exception("No valid export location")
                }

                if (outputStream == null) throw Exception("Could not open output stream")

                file.inputStream().use { input ->
                    outputStream.use { out ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                            bytesCopied += read
                            withContext(Dispatchers.Main) {
                                setProgress(bytesCopied.toFloat() / totalBytesToCopy.toFloat())
                            }
                        }
                    }
                }

                if (outUri != null) {
                    savedUris.add(outUri)
                }
            }

            withContext(Dispatchers.Main) {
                val pathMsg = if (!safUri.isNullOrEmpty()) "Export Folder" else "Downloads/DevVault"
                Toast.makeText(context, "Extracted ${savedUris.size} file(s) to $pathMsg", Toast.LENGTH_LONG).show()
                if (savedUris.size == 1) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.android.package-archive"
                        putExtra(Intent.EXTRA_STREAM, savedUris.first())
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share APK"))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } finally {
            withContext(Dispatchers.Main) {
                setExtracting(null)
                setProgress(null)
            }
        }
    }
}
