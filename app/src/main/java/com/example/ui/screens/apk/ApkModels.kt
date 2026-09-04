package com.example.ui.screens.apk

import android.net.Uri
import java.io.File

enum class ApkPackageType(val displayName: String, val badge: String) {
    STANDALONE_APK("Standard Standalone APK", "APK"),
    SPLIT_PACKAGE("Split APK Package (App Bundle)", "SPLIT"),
    APKS_BUNDLE("Unified APKs Bundle", "APKS"),
    XAPK_BUNDLE("XAPK Multi-Component Archive", "XAPK"),
    UNKNOWN("Unknown / Unrecognized Package", "RAW")
}

enum class ComponentType(val title: String) {
    BASE("Base APK (Core Code & Manifest)"),
    ABI("Native Binary Split (CPU Architecture)"),
    DENSITY("Screen Density Split (UI Resources)"),
    LANGUAGE("Localization Split (Translations)"),
    FEATURE("Dynamic Feature Module"),
    ASSET("Game / App Asset Pack"),
    UNKNOWN("Component Split")
}

data class ApkComponentInfo(
    val name: String,
    val size: Long,
    val type: ComponentType,
    val details: String,
    val filePath: String? = null
)

data class ApkPackageDetails(
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val packageType: ApkPackageType,
    val totalSize: Long,
    val components: List<ApkComponentInfo>,
    val isSystem: Boolean = false,
    val minSdk: Int = 0,
    val targetSdk: Int = 0,
    val mainApkPath: String = ""
)

data class ExtractionProgressState(
    val isActive: Boolean = false,
    val appName: String = "",
    val stage: String = "",
    val currentFile: String = "",
    val progress: Float = 0f,
    val bytesProcessed: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val elapsedSeconds: Long = 0L,
    val remainingSeconds: Long = 0L,
    val error: String? = null,
    val isCancelled: Boolean = false
)

data class ExtractionResult(
    val outputFile: File,
    val outputUri: Uri? = null,
    val appName: String,
    val packageName: String,
    val versionName: String,
    val isSplitBundle: Boolean,
    val isUniversalApk: Boolean,
    val sizeBytes: Long,
    val componentCount: Int
)

enum class ExtractionMode {
    UNIFIED_APKS_BUNDLE,
    UNIVERSAL_STANDALONE_APK,
    RAW_SPLITS_FOLDER
}
