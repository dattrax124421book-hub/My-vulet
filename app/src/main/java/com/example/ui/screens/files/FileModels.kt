package com.example.ui.screens.files

import android.content.Context
import android.text.format.Formatter
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class FileCategory(val label: String, val iconName: String) {
    ALL("All Files", "folder"),
    IMAGES("Images", "image"),
    VIDEOS("Videos", "video"),
    AUDIO("Audio", "audio"),
    DOCUMENTS("Documents", "description"),
    DOWNLOADS("Downloads", "download"),
    ARCHIVES("Archives", "archive"),
    CODE("Code", "code"),
    APKS("APKs", "android")
}

enum class FileType {
    DIRECTORY,
    IMAGE,
    VIDEO,
    AUDIO,
    PDF,
    DOCUMENT,
    ARCHIVE,
    APK,
    CODE,
    TEXT,
    UNKNOWN
}

enum class SortBy(val label: String) {
    NAME_ASC("Name (A to Z)"),
    NAME_DESC("Name (Z to A)"),
    DATE_NEWEST("Date (Newest)"),
    DATE_OLDEST("Date (Oldest)"),
    SIZE_LARGEST("Size (Largest)"),
    SIZE_SMALLEST("Size (Smallest)"),
    TYPE("File Type")
}

enum class FileViewMode {
    LIST,
    GRID
}

enum class ClipboardAction {
    COPY,
    CUT
}

data class FileClipboard(
    val action: ClipboardAction,
    val files: List<File>
)

data class ZipEntryItem(
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    val time: Long
)

data class FileMetadata(
    val file: File,
    val name: String,
    val path: String,
    val size: Long,
    val formattedSize: String,
    val lastModified: Long,
    val isDirectory: Boolean,
    val isHidden: Boolean,
    val canRead: Boolean,
    val canWrite: Boolean,
    val itemCount: Int,
    val fileType: FileType,
    val mimeType: String,
    val md5Hash: String = ""
)

data class StorageAnalytics(
    val totalBytes: Long = 0L,
    val usedBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val imageBytes: Long = 0L,
    val videoBytes: Long = 0L,
    val audioBytes: Long = 0L,
    val documentBytes: Long = 0L,
    val archiveBytes: Long = 0L,
    val codeBytes: Long = 0L,
    val apkBytes: Long = 0L,
    val otherBytes: Long = 0L,
    val largestFiles: List<File> = emptyList(),
    val emptyFolders: List<File> = emptyList(),
    val duplicateGroups: List<List<File>> = emptyList()
)

object FileUtils {
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "svg")
    private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "avi", "3gp", "mov", "flv", "wmv", "m4v")
    private val AUDIO_EXT = setOf("mp3", "wav", "ogg", "m4a", "flac", "aac", "mid", "xmf", "mxmf", "rtttl", "rtx", "ota")
    private val ARCHIVE_EXT = setOf("zip", "tar", "gz", "tgz", "rar", "7z", "bz2")
    private val CODE_EXT = setOf(
        "kt", "java", "kts", "json", "xml", "html", "htm", "css", "js", "ts", "jsx", "tsx",
        "py", "sh", "c", "cpp", "h", "hpp", "cs", "rb", "go", "rs", "php", "sql", "yaml", "yml", "gradle", "properties"
    )
    private val TEXT_EXT = setOf("txt", "csv", "log", "md", "conf", "ini")
    private val DOC_EXT = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf")

    fun getFileType(file: File): FileType {
        if (file.isDirectory) return FileType.DIRECTORY
        val ext = file.extension.lowercase(Locale.ROOT)
        return when {
            IMAGE_EXT.contains(ext) -> FileType.IMAGE
            VIDEO_EXT.contains(ext) -> FileType.VIDEO
            AUDIO_EXT.contains(ext) -> FileType.AUDIO
            ext == "pdf" -> FileType.PDF
            DOC_EXT.contains(ext) -> FileType.DOCUMENT
            ARCHIVE_EXT.contains(ext) -> FileType.ARCHIVE
            ext == "apk" || ext == "xapk" || ext == "apks" || ext == "apkm" -> FileType.APK
            CODE_EXT.contains(ext) -> FileType.CODE
            TEXT_EXT.contains(ext) -> FileType.TEXT
            else -> FileType.UNKNOWN
        }
    }

    fun getMimeType(file: File): String {
        val ext = file.extension.lowercase(Locale.ROOT)
        return when {
            IMAGE_EXT.contains(ext) -> "image/*"
            VIDEO_EXT.contains(ext) -> "video/*"
            AUDIO_EXT.contains(ext) -> "audio/*"
            ext == "pdf" -> "application/pdf"
            ext == "apk" || ext == "apks" || ext == "xapk" || ext == "apkm" -> "application/vnd.android.package-archive"
            ARCHIVE_EXT.contains(ext) -> "application/zip"
            CODE_EXT.contains(ext) || TEXT_EXT.contains(ext) -> "text/plain"
            DOC_EXT.contains(ext) -> "application/msword"
            else -> "*/*"
        }
    }

    fun formatSize(context: Context, bytes: Long): String {
        return Formatter.formatFileSize(context, bytes)
    }

    fun computeMD5(file: File): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    md.update(buffer, 0, read)
                }
            }
            val digest = md.digest()
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "Unavailable"
        }
    }

    fun getFolderItemCount(dir: File): Pair<Int, Long> {
        var count = 0
        var totalSize = 0L
        try {
            dir.walkTopDown().maxDepth(3).forEach { file ->
                if (file.isFile) {
                    count++
                    totalSize += file.length()
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return Pair(count, totalSize)
    }
}
