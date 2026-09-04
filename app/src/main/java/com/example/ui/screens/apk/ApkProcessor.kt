package com.example.ui.screens.apk

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.*
import java.security.*
import java.security.cert.X509Certificate
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

object ApkProcessor {

    private const val BUFFER_SIZE = 65536 // 64 KB high-performance streaming buffer

    fun getExportDirectory(context: Context): File {
        val devVaultDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DevVault")
        if (!devVaultDir.exists()) {
            devVaultDir.mkdirs()
        }
        return if (devVaultDir.canWrite()) devVaultDir else context.getExternalFilesDir(null) ?: context.filesDir
    }

    fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('_')
    }

    fun classifyComponent(name: String): ComponentType {
        val lower = name.lowercase()
        return when {
            lower.contains("base") -> ComponentType.BASE
            lower.contains("arm64") || lower.contains("armeabi") || lower.contains("x86") -> ComponentType.ABI
            lower.contains("dpi") || lower.contains("hdpi") || lower.contains("mdpi") || lower.contains("ldpi") -> ComponentType.DENSITY
            lower.contains("config.en") || lower.contains("config.es") || lower.contains("config.fr") || lower.contains("config.ar") || lower.contains("lang") -> ComponentType.LANGUAGE
            lower.contains("feature") || lower.contains("dynamic") -> ComponentType.FEATURE
            lower.contains("asset") -> ComponentType.ASSET
            else -> ComponentType.UNKNOWN
        }
    }

    /**
     * Inspect any APK, APKS, or XAPK file from disk and return its package metadata
     */
    suspend fun inspectApkFile(context: Context, file: File): ApkPackageDetails? = withContext(Dispatchers.IO) {
        try {
            val ext = file.extension.lowercase()
            if (ext == "apk") {
                val pi = context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_META_DATA)
                if (pi != null) {
                    val appName = pi.applicationInfo?.let { context.packageManager.getApplicationLabel(it).toString() } ?: file.nameWithoutExtension
                    val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) pi.applicationInfo?.minSdkVersion ?: 0 else 0
                    val targetSdk = pi.applicationInfo?.targetSdkVersion ?: 0
                    val comp = ApkComponentInfo(
                        name = file.name,
                        size = file.length(),
                        type = ComponentType.BASE,
                        details = "Standalone Standard APK",
                        filePath = file.absolutePath
                    )
                    return@withContext ApkPackageDetails(
                        appName = appName,
                        packageName = pi.packageName,
                        versionName = pi.versionName ?: "1.0",
                        versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong(),
                        packageType = ApkPackageType.STANDALONE_APK,
                        totalSize = file.length(),
                        components = listOf(comp),
                        minSdk = minSdk,
                        targetSdk = targetSdk,
                        mainApkPath = file.absolutePath
                    )
                }
            } else if (ext == "apks" || ext == "xapk" || ext == "zip") {
                // Inspect ZIP archive entries
                val components = mutableListOf<ApkComponentInfo>()
                var totalSize = file.length()
                var appName = file.nameWithoutExtension
                var packageName = "unknown.bundle"
                var versionName = "1.0"
                var versionCode = 1L
                var minSdk = 21
                var targetSdk = 34

                ZipFile(file).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name.endsWith(".apk", ignoreCase = true)) {
                            val cType = classifyComponent(entry.name)
                            components.add(
                                ApkComponentInfo(
                                    name = entry.name.substringAfterLast('/'),
                                    size = entry.size.takeIf { it > 0 } ?: entry.compressedSize,
                                    type = cType,
                                    details = cType.title
                                )
                            )

                            // If this is base.apk, inspect its manifest
                            if (cType == ComponentType.BASE || entry.name.contains("base", ignoreCase = true)) {
                                try {
                                    val tempBase = File.createTempFile("temp_base_", ".apk", context.cacheDir)
                                    zip.getInputStream(entry).use { input ->
                                        tempBase.outputStream().use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                    val pi = context.packageManager.getPackageArchiveInfo(tempBase.absolutePath, 0)
                                    if (pi != null) {
                                        packageName = pi.packageName
                                        versionName = pi.versionName ?: versionName
                                        versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong()
                                        appName = pi.applicationInfo?.let { context.packageManager.getApplicationLabel(it).toString() } ?: appName
                                    }
                                    tempBase.delete()
                                } catch (ignored: Exception) {}
                            }
                        } else if (entry.name.equals("manifest.json", ignoreCase = true)) {
                            try {
                                val jsonStr = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                                val json = JSONObject(jsonStr)
                                packageName = json.optString("package_name", packageName)
                                versionName = json.optString("version_name", versionName)
                                appName = json.optString("name", appName)
                            } catch (ignored: Exception) {}
                        }
                    }
                }

                if (components.isNotEmpty()) {
                    return@withContext ApkPackageDetails(
                        appName = appName,
                        packageName = packageName,
                        versionName = versionName,
                        versionCode = versionCode,
                        packageType = if (ext == "xapk") ApkPackageType.XAPK_BUNDLE else ApkPackageType.APKS_BUNDLE,
                        totalSize = totalSize,
                        components = components,
                        minSdk = minSdk,
                        targetSdk = targetSdk,
                        mainApkPath = file.absolutePath
                    )
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fast streaming copy for single standalone APK
     */
    suspend fun extractSingleApk(
        context: Context,
        sourceApk: File,
        appName: String,
        packageName: String,
        versionName: String,
        onProgress: (ExtractionProgressState) -> Unit
    ): ExtractionResult = withContext(Dispatchers.IO) {
        val outDir = getExportDirectory(context)
        val cleanName = sanitizeFileName(appName)
        val cleanVer = sanitizeFileName(versionName)
        val outFile = File(outDir, "${cleanName}_v${cleanVer}.apk")

        val totalBytes = sourceApk.length()
        var bytesProcessed = 0L
        val startTime = System.currentTimeMillis()
        var lastUpdate = 0L

        try {
            sourceApk.inputStream().buffered(BUFFER_SIZE).use { input ->
                outFile.outputStream().buffered(BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        coroutineContext.ensureActive()
                        output.write(buffer, 0, read)
                        bytesProcessed += read

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 100 || bytesProcessed == totalBytes) {
                            lastUpdate = now
                            val elapsed = (now - startTime) / 1000
                            val speed = if (elapsed > 0) bytesProcessed / elapsed else 0L
                            val remaining = if (speed > 0) (totalBytes - bytesProcessed) / speed else 0L
                            val progress = if (totalBytes > 0) bytesProcessed.toFloat() / totalBytes else 0f

                            onProgress(
                                ExtractionProgressState(
                                    isActive = true,
                                    appName = appName,
                                    stage = "Streaming APK binary...",
                                    currentFile = sourceApk.name,
                                    progress = progress,
                                    bytesProcessed = bytesProcessed,
                                    totalBytes = totalBytes,
                                    speedBytesPerSec = speed,
                                    elapsedSeconds = elapsed,
                                    remainingSeconds = remaining
                                )
                            )
                        }
                    }
                    output.flush()
                }
            }

            ExtractionResult(
                outputFile = outFile,
                appName = appName,
                packageName = packageName,
                versionName = versionName,
                isSplitBundle = false,
                isUniversalApk = false,
                sizeBytes = outFile.length(),
                componentCount = 1
            )
        } catch (e: Exception) {
            if (outFile.exists()) outFile.delete()
            throw e
        }
    }

    /**
     * Creates a high-speed unified APKs bundle (.apks) containing all splits
     */
    suspend fun createApksBundle(
        context: Context,
        baseApk: File,
        splitApks: List<File>,
        appName: String,
        packageName: String,
        versionName: String,
        onProgress: (ExtractionProgressState) -> Unit
    ): ExtractionResult = withContext(Dispatchers.IO) {
        val outDir = getExportDirectory(context)
        val cleanName = sanitizeFileName(appName)
        val cleanVer = sanitizeFileName(versionName)
        val outFile = File(outDir, "${cleanName}_v${cleanVer}.apks")

        val allFiles = listOf(baseApk) + splitApks
        val totalBytes = allFiles.sumOf { it.length() }
        var bytesProcessed = 0L
        val startTime = System.currentTimeMillis()
        var lastUpdate = 0L

        try {
            FileOutputStream(outFile).buffered(BUFFER_SIZE).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    // Set compression to fastest because APK files inside are already compressed
                    zos.setLevel(Deflater.BEST_SPEED)

                    // 1. Add metadata table of contents
                    val tocJson = JSONObject().apply {
                        put("package_name", packageName)
                        put("version_name", versionName)
                        put("app_name", appName)
                        put("created_by", "DevVault APK Extractor Pro")
                        put("splits_count", allFiles.size)
                    }
                    val tocEntry = ZipEntry("manifest.json")
                    zos.putNextEntry(tocEntry)
                    zos.write(tocJson.toString(2).toByteArray(Charsets.UTF_8))
                    zos.closeEntry()

                    // 2. Stream all APK components
                    for (file in allFiles) {
                        coroutineContext.ensureActive()
                        val isBase = file.absolutePath == baseApk.absolutePath
                        val entryName = if (isBase) "base.apk" else file.name
                        val zipEntry = ZipEntry(entryName)
                        zos.putNextEntry(zipEntry)

                        file.inputStream().buffered(BUFFER_SIZE).use { fis ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var read: Int
                            while (fis.read(buffer).also { read = it } != -1) {
                                coroutineContext.ensureActive()
                                zos.write(buffer, 0, read)
                                bytesProcessed += read

                                val now = System.currentTimeMillis()
                                if (now - lastUpdate > 100 || bytesProcessed == totalBytes) {
                                    lastUpdate = now
                                    val elapsed = (now - startTime) / 1000
                                    val speed = if (elapsed > 0) bytesProcessed / elapsed else 0L
                                    val remaining = if (speed > 0) (totalBytes - bytesProcessed) / speed else 0L
                                    val progress = if (totalBytes > 0) bytesProcessed.toFloat() / totalBytes else 0f

                                    onProgress(
                                        ExtractionProgressState(
                                            isActive = true,
                                            appName = appName,
                                            stage = "Bundling ${if (isBase) "Base APK" else "Split Config"}...",
                                            currentFile = entryName,
                                            progress = progress,
                                            bytesProcessed = bytesProcessed,
                                            totalBytes = totalBytes,
                                            speedBytesPerSec = speed,
                                            elapsedSeconds = elapsed,
                                            remainingSeconds = remaining
                                        )
                                    )
                                }
                            }
                        }
                        zos.closeEntry()
                    }
                    zos.finish()
                    fos.flush()
                }
            }

            ExtractionResult(
                outputFile = outFile,
                appName = appName,
                packageName = packageName,
                versionName = versionName,
                isSplitBundle = true,
                isUniversalApk = false,
                sizeBytes = outFile.length(),
                componentCount = allFiles.size
            )
        } catch (e: Exception) {
            if (outFile.exists()) outFile.delete()
            throw e
        }
    }

    /**
     * Merges Split APKs into a single Universal standalone installable APK
     */
    suspend fun createUniversalApk(
        context: Context,
        baseApk: File,
        splitApks: List<File>,
        appName: String,
        packageName: String,
        versionName: String,
        onProgress: (ExtractionProgressState) -> Unit
    ): ExtractionResult = withContext(Dispatchers.IO) {
        val outDir = getExportDirectory(context)
        val cleanName = sanitizeFileName(appName)
        val outFile = File(outDir, "${cleanName}_Universal.apk")

        val totalBytes = (baseApk.length() + splitApks.sumOf { it.length() })
        var bytesProcessed = 0L
        val startTime = System.currentTimeMillis()
        var lastUpdate = 0L

        try {
            val writtenEntries = HashSet<String>()
            var nextDexIndex = 1

            FileOutputStream(outFile).buffered(BUFFER_SIZE).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    zos.setLevel(Deflater.BEST_SPEED)

                    // 1. Process base.apk
                    onProgress(
                        ExtractionProgressState(
                            isActive = true,
                            appName = appName,
                            stage = "Merging Base APK structure...",
                            currentFile = "base.apk",
                            progress = 0.1f,
                            bytesProcessed = bytesProcessed,
                            totalBytes = totalBytes
                        )
                    )

                    ZipFile(baseApk).use { baseZip ->
                        // Find highest classesN.dex
                        val entries = baseZip.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            val name = entry.name
                            if (name == "classes.dex") {
                                if (nextDexIndex < 2) nextDexIndex = 2
                            } else if (name.startsWith("classes") && name.endsWith(".dex")) {
                                val numStr = name.removePrefix("classes").removeSuffix(".dex")
                                numStr.toIntOrNull()?.let { num ->
                                    if (num >= nextDexIndex) nextDexIndex = num + 1
                                }
                            }
                        }

                        // Write base entries (skip signature files so universal apk can be cleanly re-packaged)
                        val writeEntries = baseZip.entries()
                        while (writeEntries.hasMoreElements()) {
                            coroutineContext.ensureActive()
                            val entry = writeEntries.nextElement()
                            val name = entry.name
                            if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC"))) {
                                continue
                            }
                            if (writtenEntries.add(name)) {
                                val newEntry = ZipEntry(name)
                                zos.putNextEntry(newEntry)
                                baseZip.getInputStream(entry).use { input ->
                                    input.copyTo(zos, BUFFER_SIZE)
                                }
                                zos.closeEntry()
                                bytesProcessed += entry.compressedSize.coerceAtLeast(entry.size)
                            }
                        }
                    }

                    // 2. Process each split APK (merge native libs, assets, and extra dex)
                    for ((idx, splitFile) in splitApks.withIndex()) {
                        coroutineContext.ensureActive()
                        val splitName = splitFile.name
                        onProgress(
                            ExtractionProgressState(
                                isActive = true,
                                appName = appName,
                                stage = "Merging split (${idx + 1}/${splitApks.size}): $splitName",
                                currentFile = splitName,
                                progress = (bytesProcessed.toFloat() / totalBytes).coerceIn(0.1f, 0.95f),
                                bytesProcessed = bytesProcessed,
                                totalBytes = totalBytes
                            )
                        )

                        ZipFile(splitFile).use { splitZip ->
                            val splitEntries = splitZip.entries()
                            while (splitEntries.hasMoreElements()) {
                                coroutineContext.ensureActive()
                                val entry = splitEntries.nextElement()
                                val name = entry.name

                                // Skip split AndroidManifest and signatures
                                if (name == "AndroidManifest.xml" || name.startsWith("META-INF/")) {
                                    continue
                                }

                                // Handle Dex files: rename to classes<N>.dex
                                if (name.endsWith(".dex")) {
                                    val targetDexName = if (nextDexIndex == 1) "classes.dex" else "classes${nextDexIndex}.dex"
                                    nextDexIndex++
                                    if (writtenEntries.add(targetDexName)) {
                                        zos.putNextEntry(ZipEntry(targetDexName))
                                        splitZip.getInputStream(entry).use { it.copyTo(zos, BUFFER_SIZE) }
                                        zos.closeEntry()
                                    }
                                } else if (name.startsWith("lib/") || name.startsWith("assets/") || name.startsWith("res/")) {
                                    // Copy native libraries, assets, and split resources if not present
                                    if (writtenEntries.add(name)) {
                                        zos.putNextEntry(ZipEntry(name))
                                        splitZip.getInputStream(entry).use { it.copyTo(zos, BUFFER_SIZE) }
                                        zos.closeEntry()
                                    }
                                }
                                bytesProcessed += entry.compressedSize.coerceAtLeast(entry.size)
                            }
                        }
                    }

                    zos.finish()
                    fos.flush()
                }
            }

            ExtractionResult(
                outputFile = outFile,
                appName = appName,
                packageName = packageName,
                versionName = versionName,
                isSplitBundle = false,
                isUniversalApk = true,
                sizeBytes = outFile.length(),
                componentCount = 1 + splitApks.size
            )
        } catch (e: Exception) {
            if (outFile.exists()) outFile.delete()
            throw e
        }
    }

    /**
     * Converts an existing .apks / .xapk bundle to Universal APK
     */
    suspend fun convertBundleToUniversalApk(
        context: Context,
        bundleFile: File,
        onProgress: (ExtractionProgressState) -> Unit
    ): ExtractionResult = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "apks_convert_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            var baseApk: File? = null
            val splitApks = mutableListOf<File>()

            // Extract all APKs inside the bundle
            ZipFile(bundleFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.endsWith(".apk", ignoreCase = true)) {
                        val fileName = entry.name.substringAfterLast('/')
                        val destFile = File(tempDir, fileName)
                        zip.getInputStream(entry).use { input ->
                            destFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (fileName.contains("base", ignoreCase = true) || baseApk == null) {
                            if (baseApk != null) splitApks.add(baseApk!!)
                            baseApk = destFile
                        } else {
                            splitApks.add(destFile)
                        }
                    }
                }
            }

            if (baseApk == null) {
                throw IllegalStateException("No valid APK files found inside the bundle.")
            }

            val pi = context.packageManager.getPackageArchiveInfo(baseApk!!.absolutePath, 0)
            val appName = pi?.applicationInfo?.let { context.packageManager.getApplicationLabel(it).toString() } ?: bundleFile.nameWithoutExtension.removeSuffix(".apks")
            val packageName = pi?.packageName ?: "unknown"
            val versionName = pi?.versionName ?: "1.0"

            val result = createUniversalApk(
                context = context,
                baseApk = baseApk!!,
                splitApks = splitApks,
                appName = appName,
                packageName = packageName,
                versionName = versionName,
                onProgress = onProgress
            )
            result
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Install APK or APKS using native Android PackageInstaller
     */
    suspend fun installPackage(
        context: Context,
        file: File,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val ext = file.extension.lowercase()
            if (ext == "apk") {
                // Standard single APK: Launch Android installer Intent
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                withContext(Dispatchers.Main) {
                    context.startActivity(intent)
                    onSuccess("Opening Android Package Installer...")
                }
            } else if (ext == "apks" || ext == "xapk" || ext == "zip") {
                // Split APK package: Install via PackageInstaller Session API
                val packageInstaller = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val sessionId = packageInstaller.createSession(params)
                val session = packageInstaller.openSession(sessionId)

                ZipFile(file).use { zip ->
                    val entries = zip.entries()
                    var apkCount = 0
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name.endsWith(".apk", ignoreCase = true)) {
                            apkCount++
                            val entryName = entry.name.substringAfterLast('/')
                            zip.getInputStream(entry).use { input ->
                                session.openWrite(entryName, 0, entry.size).use { output ->
                                    input.copyTo(output)
                                    session.fsync(output)
                                }
                            }
                        }
                    }
                    if (apkCount == 0) {
                        session.abandon()
                        throw IllegalStateException("No APK components found in bundle.")
                    }
                }

                // Commit session with broadcast intent
                val intent = Intent(context, ApkInstallReceiver::class.java).apply {
                    action = "com.example.devvault.ACTION_INSTALL_COMPLETE"
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                )

                session.commit(pendingIntent.intentSender)
                session.close()

                withContext(Dispatchers.Main) {
                    onSuccess("Package installer session initialized with splits. Installation prompt starting...")
                }
            } else {
                throw IllegalArgumentException("Unsupported file type: .${file.extension}")
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onError("Installation failed: ${e.localizedMessage ?: e.message}")
            }
        }
    }
}
