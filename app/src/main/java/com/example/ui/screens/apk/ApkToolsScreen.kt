package com.example.ui.screens.apk

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val nativeLibs: List<String>,
    val components: List<ApkComponentInfo> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkToolsScreen(
    onBack: () -> Unit,
    onNavigateToHex: (String) -> Unit = {},
    onOpenFileManager: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    val scope = rememberCoroutineScope()

    var apps by remember { mutableStateOf<List<ApkItem>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var selectedFilter by remember { mutableStateOf("USER") } // "ALL", "USER", "SPLITS", "SYSTEM"

    // Active extraction states
    var progressState by remember { mutableStateOf(ExtractionProgressState()) }
    var completedResult by remember { mutableStateOf<ExtractionResult?>(null) }
    var extractionJob by remember { mutableStateOf<Job?>(null) }

    // Dialog states
    var pendingSplitChoice by remember { mutableStateOf<ApkItem?>(null) }
    var inspectingApp by remember { mutableStateOf<ApkItem?>(null) }
    var inspectDetails by remember { mutableStateOf<ApkDeepDetails?>(null) }
    var isInspectingLoading by remember { mutableStateOf(false) }

    // External file picker dialog states
    var externalPickedFile by remember { mutableStateOf<File?>(null) }
    var viewingPackageDetails by remember { mutableStateOf<ApkPackageDetails?>(null) }

    // Load installed apps
    fun loadApps() {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            val mapped = installedApps.mapNotNull { appInfo ->
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

    LaunchedEffect(Unit) {
        loadApps()
    }

    // External APK Picker Launcher
    val externalFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val tempFile = File(context.cacheDir, "picked_${System.currentTimeMillis()}_${getFileName(context, uri)}")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        externalPickedFile = tempFile
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Error opening file: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Start extraction function
    fun startExtraction(app: ApkItem, mode: ExtractionMode?) {
        val splits = app.splitSourceDirs?.map { File(it) } ?: emptyList()
        val isSplit = splits.isNotEmpty()

        if (isSplit && mode == null) {
            pendingSplitChoice = app
            return
        }

        extractionJob?.cancel()
        extractionJob = scope.launch {
            progressState = ExtractionProgressState(
                isActive = true,
                appName = app.name,
                stage = "Initializing...",
                currentFile = app.name
            )
            completedResult = null

            try {
                val baseFile = File(app.baseSourceDir)
                val result = when {
                    !isSplit -> {
                        ApkProcessor.extractSingleApk(
                            context = context,
                            sourceApk = baseFile,
                            appName = app.name,
                            packageName = app.packageName,
                            versionName = app.versionName,
                            onProgress = { progressState = it }
                        )
                    }
                    mode == ExtractionMode.UNIVERSAL_STANDALONE_APK -> {
                        ApkProcessor.createUniversalApk(
                            context = context,
                            baseApk = baseFile,
                            splitApks = splits,
                            appName = app.name,
                            packageName = app.packageName,
                            versionName = app.versionName,
                            onProgress = { progressState = it }
                        )
                    }
                    mode == ExtractionMode.RAW_SPLITS_FOLDER -> {
                        // Extract separate splits to dedicated folder
                        extractRawSplits(context, app, baseFile, splits) { progressState = it }
                    }
                    else -> {
                        // UNIFIED_APKS_BUNDLE (Default recommended for split packages)
                        ApkProcessor.createApksBundle(
                            context = context,
                            baseApk = baseFile,
                            splitApks = splits,
                            appName = app.name,
                            packageName = app.packageName,
                            versionName = app.versionName,
                            onProgress = { progressState = it }
                        )
                    }
                }

                completedResult = result
                progressState = ExtractionProgressState(isActive = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                progressState = ExtractionProgressState(isActive = false, isCancelled = true)
                Toast.makeText(context, "Extraction cancelled", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                progressState = ExtractionProgressState(isActive = false, error = e.message)
                Toast.makeText(context, "Extraction failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
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
                } catch (ignored: Exception) {}

                // Build component list
                val components = mutableListOf<ApkComponentInfo>()
                val baseFile = File(app.baseSourceDir)
                components.add(
                    ApkComponentInfo(
                        name = "base.apk",
                        size = baseFile.length(),
                        type = ComponentType.BASE,
                        details = "Core Android Manifest, Dalvik Executable (DEX), base resources"
                    )
                )
                app.splitSourceDirs?.forEach { splitPath ->
                    val file = File(splitPath)
                    val cType = ApkProcessor.classifyComponent(file.name)
                    components.add(
                        ApkComponentInfo(
                            name = file.name,
                            size = file.length(),
                            type = cType,
                            details = cType.title
                        )
                    )
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
                        nativeLibs = nativeLibs,
                        components = components
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

    // Filter apps
    val filteredApps = remember(apps, searchQuery, selectedFilter) {
        apps.filter { app ->
            val matchesSearch = searchQuery.isBlank() ||
                    app.name.contains(searchQuery, ignoreCase = true) ||
                    app.packageName.contains(searchQuery, ignoreCase = true)

            val matchesFilter = when (selectedFilter) {
                "USER" -> !app.isSystem
                "SPLITS" -> !app.splitSourceDirs.isNullOrEmpty()
                "SYSTEM" -> app.isSystem
                else -> true
            }

            matchesSearch && matchesFilter
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Pro APK Extractor", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text(
                            text = "${filteredApps.size} apps available",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Pick external APK or APKS
                    IconButton(onClick = {
                        externalFilePicker.launch(arrayOf("*/*", "application/vnd.android.package-archive", "application/zip"))
                    }) {
                        Icon(Icons.Default.FileOpen, contentDescription = "Open APK / APKS File")
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
            // Search Input
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
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            // Filter Chips Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == "ALL",
                    onClick = { selectedFilter = "ALL" },
                    label = { Text("All (${apps.size})") }
                )
                FilterChip(
                    selected = selectedFilter == "USER",
                    onClick = { selectedFilter = "USER" },
                    label = { Text("User Apps (${apps.count { !it.isSystem }})") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = selectedFilter == "SPLITS",
                    onClick = { selectedFilter = "SPLITS" },
                    label = { Text("Split Bundles (${apps.count { !it.splitSourceDirs.isNullOrEmpty() }})") },
                    leadingIcon = { Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = selectedFilter == "SYSTEM",
                    onClick = { selectedFilter = "SYSTEM" },
                    label = { Text("System (${apps.count { it.isSystem }})") },
                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }

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
                    items(filteredApps, key = { it.packageName }) { app ->
                        val hasSplits = !app.splitSourceDirs.isNullOrEmpty()
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { loadDeepDetails(app) }
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(14.dp)
                                    .fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Image(
                                        bitmap = app.icon.toBitmap().asImageBitmap(),
                                        contentDescription = app.name,
                                        modifier = Modifier.size(46.dp)
                                    )
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                app.name,
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                                modifier = Modifier.weight(1f, fill = false),
                                                maxLines = 1
                                            )
                                            if (hasSplits) {
                                                Spacer(Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = MaterialTheme.colorScheme.tertiaryContainer
                                                ) {
                                                    Text(
                                                        text = "SPLIT (${app.splitSourceDirs!!.size + 1})",
                                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            app.packageName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 2.dp)
                                        ) {
                                            Text("v${app.versionName} (${app.versionCode})", style = MaterialTheme.typography.labelSmall)
                                            Text(
                                                Formatter.formatShortFileSize(context, app.totalSize),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

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
                                            startExtraction(app, null)
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Extract APK", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Split Extraction Choice Bottom Sheet
    pendingSplitChoice?.let { app ->
        SplitExtractionChoiceDialog(
            app = app,
            onDismiss = { pendingSplitChoice = null },
            onSelectMode = { mode ->
                pendingSplitChoice = null
                startExtraction(app, mode)
            }
        )
    }

    // Professional Extraction Progress & Completion Dialog
    if (progressState.isActive || completedResult != null) {
        ApkExtractionProgressDialog(
            progressState = progressState,
            completedResult = completedResult,
            onCancel = {
                extractionJob?.cancel()
                progressState = ExtractionProgressState(isActive = false, isCancelled = true)
                Toast.makeText(context, "Extraction cancelled", Toast.LENGTH_SHORT).show()
            },
            onDismissCompleted = {
                completedResult = null
            },
            onInstall = { file ->
                scope.launch {
                    ApkProcessor.installPackage(
                        context = context,
                        file = file,
                        onSuccess = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
                        onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                    )
                }
            },
            onShare = { file ->
                shareApkFile(context, file)
            },
            onViewComponents = { file ->
                scope.launch(Dispatchers.IO) {
                    val details = ApkProcessor.inspectApkFile(context, file)
                    withContext(Dispatchers.Main) {
                        viewingPackageDetails = details
                    }
                }
            },
            onOpenFolder = { file ->
                file.parentFile?.let { folder ->
                    onOpenFileManager?.invoke(folder.absolutePath)
                        ?: Toast.makeText(context, "Saved to: ${folder.absolutePath}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    // External Picked File Action Dialog (inspired by screenshot 3)
    externalPickedFile?.let { file ->
        ApkActionDialog(
            file = file,
            onDismiss = { externalPickedFile = null },
            onInstall = { targetFile ->
                scope.launch {
                    ApkProcessor.installPackage(
                        context = context,
                        file = targetFile,
                        onSuccess = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
                        onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                    )
                }
            },
            onViewComponents = { targetFile ->
                scope.launch(Dispatchers.IO) {
                    val details = ApkProcessor.inspectApkFile(context, targetFile)
                    withContext(Dispatchers.Main) {
                        viewingPackageDetails = details
                    }
                }
            },
            onConvertToApk = { targetFile ->
                extractionJob?.cancel()
                extractionJob = scope.launch {
                    try {
                        progressState = ExtractionProgressState(
                            isActive = true,
                            appName = targetFile.nameWithoutExtension,
                            stage = "Converting bundle to Universal APK...",
                            currentFile = targetFile.name
                        )
                        val res = ApkProcessor.convertBundleToUniversalApk(
                            context = context,
                            bundleFile = targetFile,
                            onProgress = { progressState = it }
                        )
                        completedResult = res
                        progressState = ExtractionProgressState(isActive = false)
                    } catch (e: Exception) {
                        progressState = ExtractionProgressState(isActive = false, error = e.message)
                        Toast.makeText(context, "Conversion failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onExtractSplits = { targetFile ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val outDir = File(ApkProcessor.getExportDirectory(context), "${targetFile.nameWithoutExtension}_extracted")
                        outDir.mkdirs()
                        ZipFile(targetFile).use { zip ->
                            val entries = zip.entries()
                            while (entries.hasMoreElements()) {
                                val entry = entries.nextElement()
                                if (!entry.isDirectory) {
                                    val dest = File(outDir, entry.name.substringAfterLast('/'))
                                    zip.getInputStream(entry).use { input ->
                                        dest.outputStream().use { output -> input.copyTo(output) }
                                    }
                                }
                            }
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Extracted to: ${outDir.name}", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Extract failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onProperties = { targetFile ->
                scope.launch(Dispatchers.IO) {
                    val details = ApkProcessor.inspectApkFile(context, targetFile)
                    withContext(Dispatchers.Main) {
                        viewingPackageDetails = details
                    }
                }
            },
            onShare = { targetFile ->
                shareApkFile(context, targetFile)
            }
        )
    }

    // Components Breakdown Bottom Sheet
    viewingPackageDetails?.let { details ->
        ApkComponentsViewerDialog(
            details = details,
            onDismiss = { viewingPackageDetails = null },
            onInstall = {
                File(details.mainApkPath).takeIf { it.exists() }?.let { apkFile ->
                    scope.launch {
                        ApkProcessor.installPackage(
                            context = context,
                            file = apkFile,
                            onSuccess = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
                            onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                        )
                    }
                }
            },
            onConvertToUniversal = {
                File(details.mainApkPath).takeIf { it.exists() }?.let { bundleFile ->
                    extractionJob?.cancel()
                    extractionJob = scope.launch {
                        try {
                            progressState = ExtractionProgressState(
                                isActive = true,
                                appName = details.appName,
                                stage = "Converting to Universal APK...",
                                currentFile = bundleFile.name
                            )
                            val res = ApkProcessor.convertBundleToUniversalApk(
                                context = context,
                                bundleFile = bundleFile,
                                onProgress = { progressState = it }
                            )
                            completedResult = res
                            progressState = ExtractionProgressState(isActive = false)
                        } catch (e: Exception) {
                            progressState = ExtractionProgressState(isActive = false, error = e.message)
                            Toast.makeText(context, "Conversion failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
    }

    // Deep Inspection Bottom Sheet
    if (inspectingApp != null) {
        val targetApp = inspectingApp!!
        ModalBottomSheet(
            onDismissRequest = {
                inspectingApp = null
                inspectDetails = null
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        bitmap = targetApp.icon.toBitmap().asImageBitmap(),
                        contentDescription = targetApp.name,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(targetApp.name, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                        Text(targetApp.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        Text(
                            "v${targetApp.versionName} • Code ${targetApp.versionCode}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                if (isInspectingLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (inspectDetails != null) {
                    val d = inspectDetails!!

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Split components info
                        if (d.components.size > 1) {
                            item {
                                Card(
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            "⚡ Split App Bundle (${d.components.size} components)",
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        d.components.forEach { comp ->
                                            Text(
                                                "• ${comp.name} (${Formatter.formatShortFileSize(context, comp.size)})",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            InfoSectionHeader("Package Metrics")
                            Text("Total Size: ${Formatter.formatShortFileSize(context, targetApp.totalSize)}", style = MaterialTheme.typography.bodySmall)
                            Text("Target SDK: Android ${targetApp.targetSdk} (API ${targetApp.targetSdk})", style = MaterialTheme.typography.bodySmall)
                            Text("Min SDK: Android ${targetApp.minSdk} (API ${targetApp.minSdk})", style = MaterialTheme.typography.bodySmall)
                            Text("Type: ${if (targetApp.isSystem) "System Pre-installed" else "User Installed"}", style = MaterialTheme.typography.bodySmall)
                        }

                        item {
                            InfoSectionHeader("Manifest Components")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Activities: ${d.activitiesCount}", style = MaterialTheme.typography.bodySmall)
                                Text("Services: ${d.servicesCount}", style = MaterialTheme.typography.bodySmall)
                                Text("Receivers: ${d.receiversCount}", style = MaterialTheme.typography.bodySmall)
                                Text("Providers: ${d.providersCount}", style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        if (d.nativeLibs.isNotEmpty()) {
                            item {
                                InfoSectionHeader("Native Architectures (ABI)")
                                Text(
                                    d.nativeLibs.joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }

                        item {
                            InfoSectionHeader("Cryptographic Signatures")
                            CopyableHashRow("SHA-256", d.signatureSha256, context)
                            CopyableHashRow("MD5", d.signatureMd5, context)
                        }

                        item {
                            InfoSectionHeader("Declared Permissions (${d.permissions.size})")
                            d.permissions.take(20).forEach { perm ->
                                Text(
                                    text = "• ${perm.substringAfterLast('.')}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (perm.contains("STORAGE") || perm.contains("CAMERA") || perm.contains("LOCATION")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (d.permissions.size > 20) {
                                Text(
                                    "+ ${d.permissions.size - 20} more permissions",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            inspectingApp = null
                            onNavigateToHex(targetApp.baseSourceDir)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.DataArray, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Hex View")
                    }

                    Button(
                        onClick = {
                            val target = targetApp
                            inspectingApp = null
                            startExtraction(target, null)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Extract APK")
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private suspend fun extractRawSplits(
    context: Context,
    app: ApkItem,
    baseApk: File,
    splits: List<File>,
    onProgress: (ExtractionProgressState) -> Unit
): ExtractionResult = withContext(Dispatchers.IO) {
    val outDir = File(ApkProcessor.getExportDirectory(context), "${ApkProcessor.sanitizeFileName(app.name)}_splits")
    outDir.mkdirs()

    val all = listOf(baseApk) + splits
    val totalBytes = all.sumOf { it.length() }
    var copied = 0L

    for (file in all) {
        val destName = if (file == baseApk) "base.apk" else file.name
        val destFile = File(outDir, destName)
        file.inputStream().buffered(65536).use { input ->
            destFile.outputStream().buffered(65536).use { output ->
                val buf = ByteArray(65536)
                var r: Int
                while (input.read(buf).also { r = it } != -1) {
                    output.write(buf, 0, r)
                    copied += r
                    onProgress(
                        ExtractionProgressState(
                            isActive = true,
                            appName = app.name,
                            stage = "Copying raw split...",
                            currentFile = destName,
                            progress = copied.toFloat() / totalBytes,
                            bytesProcessed = copied,
                            totalBytes = totalBytes
                        )
                    )
                }
            }
        }
    }

    ExtractionResult(
        outputFile = outDir,
        appName = app.name,
        packageName = app.packageName,
        versionName = app.versionName,
        isSplitBundle = true,
        isUniversalApk = false,
        sizeBytes = totalBytes,
        componentCount = all.size
    )
}

private fun shareApkFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share APK"))
    } catch (e: Exception) {
        Toast.makeText(context, "Share error: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun getFileName(context: Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) result = it.getString(index)
            }
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/')
        if (cut != null && cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result ?: "package.apk"
}

@Composable
private fun InfoSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun CopyableHashRow(label: String, value: String, context: Context) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
                Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(value, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), maxLines = 1)
        }
        Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = "Copy",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.outline
        )
    }
}
