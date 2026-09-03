package com.example.ui.screens.apps

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppItem(
    val name: String, 
    val packageName: String, 
    val icon: android.graphics.drawable.Drawable,
    val permissions: List<String>,
    val riskScore: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    
    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val installedApps = packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                .filter { (it.applicationInfo!!.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { packageInfo ->
                    val perms = packageInfo.requestedPermissions?.toList() ?: emptyList()
                    val dangerousCount = perms.count { it.contains("LOCATION") || it.contains("CONTACTS") || it.contains("CAMERA") || it.contains("MICROPHONE") || it.contains("STORAGE") }
                    val score = when {
                        dangerousCount >= 5 -> "High"
                        dangerousCount in 2..4 -> "Moderate"
                        else -> "Low"
                    }
                    
                    AppItem(
                        name = packageManager.getApplicationLabel(packageInfo.applicationInfo!!).toString(),
                        packageName = packageInfo.packageName,
                        icon = packageManager.getApplicationIcon(packageInfo.applicationInfo!!),
                        permissions = perms,
                        riskScore = score
                    )
                }
                .sortedBy { it.name.lowercase() }
            
            withContext(Dispatchers.Main) {
                apps = installedApps
                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Installed Apps") })
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = "Permission and behavior risk indicator\nPermission risk is not malware detection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                items(apps) { app ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Image(
                                    bitmap = app.icon.toBitmap().asImageBitmap(),
                                    contentDescription = app.name,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.name, style = MaterialTheme.typography.titleMedium)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    text = "Risk: ${app.riskScore}", 
                                    style = MaterialTheme.typography.labelMedium,
                                    color = when(app.riskScore) {
                                        "High" -> MaterialTheme.colorScheme.error
                                        "Moderate" -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.primary
                                    }
                                )
                            }
                            if (app.permissions.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Permissions (${app.permissions.size}): ${app.permissions.take(3).joinToString { it.substringAfterLast('.') }}...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
