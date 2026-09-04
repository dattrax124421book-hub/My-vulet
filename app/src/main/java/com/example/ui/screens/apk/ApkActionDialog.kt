package com.example.ui.screens.apk

import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkActionDialog(
    file: File,
    onDismiss: () -> Unit,
    onInstall: (File) -> Unit,
    onViewComponents: (File) -> Unit,
    onConvertToApk: ((File) -> Unit)? = null,
    onExtractSplits: ((File) -> Unit)? = null,
    onProperties: (File) -> Unit,
    onShare: (File) -> Unit
) {
    val context = LocalContext.current
    val ext = file.extension.lowercase()
    val isBundle = ext == "apks" || ext == "xapk" || ext == "zip"
    val isApk = ext == "apk"

    var packageDetails by remember { mutableStateOf<ApkPackageDetails?>(null) }
    var isLoadingDetails by remember { mutableStateOf(true) }

    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            val details = ApkProcessor.inspectApkFile(context, file)
            withContext(Dispatchers.Main) {
                packageDetails = details
                isLoadingDetails = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Android,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Open with...",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Header chip with package info
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = packageDetails?.appName ?: file.nameWithoutExtension,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1
                            )
                            if (packageDetails != null) {
                                Text(
                                    text = "${packageDetails!!.packageName} • v${packageDetails!!.versionName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isBundle) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = if (isBundle) "SPLIT BUNDLE" else "STANDALONE",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (isBundle) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Action Items (matching MT Manager clean radio / action style from screenshot 3)
                ActionRowItem(
                    icon = Icons.Default.InstallMobile,
                    title = "Install",
                    subtitle = if (isBundle) "Install all split APKs via native Session installer" else "Install standalone APK",
                    accentColor = Color(0xFF10B981)
                ) {
                    onDismiss()
                    onInstall(file)
                }

                ActionRowItem(
                    icon = Icons.Default.Visibility,
                    title = "View",
                    subtitle = "Inspect components, split configs & manifest",
                    accentColor = Color(0xFF3B82F6)
                ) {
                    onDismiss()
                    onViewComponents(file)
                }

                if (isBundle) {
                    ActionRowItem(
                        icon = Icons.Default.Transform,
                        title = "Convert to apk",
                        subtitle = "Merge split APKs into a single Universal APK",
                        accentColor = Color(0xFFF59E0B)
                    ) {
                        onDismiss()
                        onConvertToApk?.invoke(file)
                    }

                    ActionRowItem(
                        icon = Icons.Default.FolderZip,
                        title = "Extract splits",
                        subtitle = "Extract inner split APKs to folder",
                        accentColor = Color(0xFF8B5CF6)
                    ) {
                        onDismiss()
                        onExtractSplits?.invoke(file)
                    }
                }

                ActionRowItem(
                    icon = Icons.Default.Info,
                    title = "Properties",
                    subtitle = "File size, hashes & path details",
                    accentColor = MaterialTheme.colorScheme.secondary
                ) {
                    onDismiss()
                    onProperties(file)
                }

                ActionRowItem(
                    icon = Icons.Default.Share,
                    title = "Share",
                    subtitle = "Send package to other devices",
                    accentColor = MaterialTheme.colorScheme.outline
                ) {
                    onDismiss()
                    onShare(file)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ActionRowItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
