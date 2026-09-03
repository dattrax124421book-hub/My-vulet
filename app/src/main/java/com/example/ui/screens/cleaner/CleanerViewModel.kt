package com.example.ui.screens.cleaner

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Calendar

data class CleanerState(
    val isScanning: Boolean = false,
    val scannedCount: Int = 0,
    val largeFiles: List<File> = emptyList(),
    val oldFiles: List<File> = emptyList(),
    val duplicates: List<List<File>> = emptyList(),
    val unusedApps: List<UnusedApp> = emptyList()
)

data class UnusedApp(
    val packageName: String,
    val name: String,
    val icon: android.graphics.drawable.Drawable,
    val size: Long
)

class CleanerViewModel : ViewModel() {
    private val _state = MutableStateFlow(CleanerState())
    val state = _state.asStateFlow()

    private var scanJob: Job? = null

    fun startScan(context: Context) {
        if (scanJob?.isActive == true) return
        
        _state.value = CleanerState(isScanning = true)
        
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            val largeFiles = mutableListOf<File>()
            val oldFiles = mutableListOf<File>()
            val sizeGroups = mutableMapOf<Long, MutableList<File>>()
            
            var scanned = 0
            
            val ninetyDaysAgo = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
            
            fun scanDir(dir: File) {
                if (!isActive) return
                val files = dir.listFiles() ?: return
                for (file in files) {
                    if (!isActive) return
                    if (file.isDirectory) {
                        scanDir(file)
                    } else {
                        scanned++
                        if (scanned % 100 == 0) {
                            _state.update { it.copy(scannedCount = scanned) }
                        }
                        
                        val length = file.length()
                        val lastModified = file.lastModified()
                        
                        if (length > 20 * 1024 * 1024) { // 20MB
                            largeFiles.add(file)
                        }
                        
                        if (lastModified < ninetyDaysAgo && length > 0) {
                            oldFiles.add(file)
                        }
                        
                        if (length > 0) {
                            val list = sizeGroups.getOrPut(length) { mutableListOf() }
                            list.add(file)
                        }
                    }
                }
            }
            
            val externalRoot = Environment.getExternalStorageDirectory()
            scanDir(externalRoot)
            
            // Filter duplicates
            val potentialDuplicates = sizeGroups.filter { it.value.size > 1 }
            val realDuplicates = mutableListOf<List<File>>()
            
            for ((_, group) in potentialDuplicates) {
                if (!isActive) break
                val hashGroups = mutableMapOf<String, MutableList<File>>()
                for (file in group) {
                    if (!isActive) break
                    val hash = computeSha256(file) ?: continue
                    hashGroups.getOrPut(hash) { mutableListOf() }.add(file)
                }
                for ((_, hashGroup) in hashGroups) {
                    if (hashGroup.size > 1) {
                        realDuplicates.add(hashGroup)
                    }
                }
            }
            
            // Usage stats
            val unusedAppsList = mutableListOf<UnusedApp>()
            try {
                val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val pm = context.packageManager
                
                val cal = Calendar.getInstance()
                cal.add(Calendar.YEAR, -1) // Query last year
                val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_MONTHLY, cal.timeInMillis, System.currentTimeMillis())
                
                val usedPackages = stats.filter { it.lastTimeUsed > ninetyDaysAgo }.map { it.packageName }.toSet()
                
                val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                for (app in installedApps) {
                    if (!isActive) break
                    if ((app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue
                    if (!usedPackages.contains(app.packageName)) {
                        val name = pm.getApplicationLabel(app).toString()
                        val icon = pm.getApplicationIcon(app)
                        val size = File(app.sourceDir).length()
                        unusedAppsList.add(UnusedApp(app.packageName, name, icon, size))
                    }
                }
            } catch (e: Exception) {
                // Permission not granted or error
            }
            
            _state.update { 
                it.copy(
                    isScanning = false,
                    scannedCount = scanned,
                    largeFiles = largeFiles.sortedByDescending { f -> f.length() },
                    oldFiles = oldFiles.sortedByDescending { f -> f.length() },
                    duplicates = realDuplicates,
                    unusedApps = unusedAppsList.sortedByDescending { a -> a.size }
                )
            }
        }
    }
    
    fun cancelScan() {
        scanJob?.cancel()
        _state.update { it.copy(isScanning = false) }
    }
    
    private fun computeSha256(file: File): String? {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val hashBytes = digest.digest()
            val sb = java.lang.StringBuilder()
            for (b in hashBytes) {
                sb.append(String.format("%02x", b))
            }
            return sb.toString()
        } catch (e: Exception) {
            return null
        }
    }
    
    fun removeFileFromLists(file: File) {
        _state.update { s ->
            val newDuplicates = s.duplicates.map { group ->
                group.filter { it.absolutePath != file.absolutePath }
            }.filter { it.size > 1 }
            
            s.copy(
                largeFiles = s.largeFiles.filter { it.absolutePath != file.absolutePath },
                oldFiles = s.oldFiles.filter { it.absolutePath != file.absolutePath },
                duplicates = newDuplicates
            )
        }
    }
}
