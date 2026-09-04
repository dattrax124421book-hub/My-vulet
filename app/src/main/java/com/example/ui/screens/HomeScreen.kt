package com.example.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PowerTool(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val route: String,
    val badge: String? = null,
    val accentColor: Color = Color(0xFF38BDF8)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current

    // Real-time Storage & RAM Metrics
    var totalStorage by remember { mutableLongStateOf(0L) }
    var freeStorage by remember { mutableLongStateOf(0L) }
    var usedStorage by remember { mutableLongStateOf(0L) }
    var storagePercent by remember { mutableFloatStateOf(0f) }

    var totalRam by remember { mutableLongStateOf(0L) }
    var freeRam by remember { mutableLongStateOf(0L) }
    var usedRam by remember { mutableLongStateOf(0L) }
    var ramPercent by remember { mutableFloatStateOf(0f) }

    fun refreshMetrics() {
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            totalStorage = stat.totalBytes
            freeStorage = stat.availableBytes
            usedStorage = totalStorage - freeStorage
            storagePercent = if (totalStorage > 0) usedStorage.toFloat() / totalStorage.toFloat() else 0f

            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            totalRam = memInfo.totalMem
            freeRam = memInfo.availMem
            usedRam = totalRam - freeRam
            ramPercent = if (totalRam > 0) usedRam.toFloat() / totalRam.toFloat() else 0f
        } catch (e: Exception) {
            // fallback
        }
    }

    LaunchedEffect(Unit) {
        refreshMetrics()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "DevVault",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-0.5).sp
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "ULTIMATE PRO",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    letterSpacing = 0.5.sp
                                ),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { onNavigate("file_manager") }) {
                        Icon(Icons.Default.Search, contentDescription = "Quick Search")
                    }
                    IconButton(onClick = { onNavigate("settings") }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Storage & Telemetry Hero Card
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigate("cleaner") }
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Storage,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Internal Storage",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                            Text(
                                text = "${(storagePercent * 100).toInt()}% Used",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (storagePercent > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        // Linear Progress Bar
                        LinearProgressIndicator(
                            progress = { storagePercent },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp)),
                            color = if (storagePercent > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surface
                        )

                        Spacer(Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Free: ${Formatter.formatShortFileSize(context, freeStorage)}",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.outline)
                            )
                            Text(
                                text = "Total: ${Formatter.formatShortFileSize(context, totalStorage)}",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.outline)
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                        Spacer(Modifier.height(10.dp))

                        // RAM Telemetry Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Memory,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("RAM", style = MaterialTheme.typography.labelMedium)
                            }
                            Text(
                                text = "${Formatter.formatShortFileSize(context, usedRam)} / ${Formatter.formatShortFileSize(context, totalRam)} (${(ramPercent * 100).toInt()}%)",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }

            // Quick Category Strip
            item {
                Text(
                    "Quick Access Categories",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CategoryChip("Files", Icons.Default.Folder, Color(0xFF38BDF8)) { onNavigate("file_manager") }
                    CategoryChip("Vault", Icons.Default.Security, Color(0xFFEF4444)) { onNavigate("vault") }
                    CategoryChip("APK Tools", Icons.Default.Android, Color(0xFF22C55E)) { onNavigate("apk_tools") }
                    CategoryChip("Wi-Fi Share", Icons.Default.WifiTethering, Color(0xFFA855F7)) { onNavigate("web_share") }
                    CategoryChip("Editor", Icons.Default.Code, Color(0xFFF59E0B)) { onNavigate("code_editor") }
                    CategoryChip("Cleaner", Icons.Default.CleaningServices, Color(0xFFEC4899)) { onNavigate("cleaner") }
                }
            }

            // Suite 1: File & Storage Center
            item {
                SectionHeader("📁 File & Storage Center")
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PowerToolRow(
                        tool = PowerTool(
                            title = "File Manager",
                            subtitle = "Browse, organize, copy, cut, compress and dual-view files",
                            icon = Icons.Default.Folder,
                            route = "file_manager",
                            badge = "Primary",
                            accentColor = Color(0xFF38BDF8)
                        ),
                        onClick = { onNavigate("file_manager") }
                    )
                    PowerToolRow(
                        tool = PowerTool(
                            title = "AES-256 Vault",
                            subtitle = "Hardware Keystore encrypted private safe for sensitive files",
                            icon = Icons.Default.Security,
                            route = "vault",
                            badge = "Secure",
                            accentColor = Color(0xFFEF4444)
                        ),
                        onClick = { onNavigate("vault") }
                    )
                    PowerToolRow(
                        tool = PowerTool(
                            title = "Storage Cleaner",
                            subtitle = "Analyze space hogs, duplicate files, old APKs & junk cache",
                            icon = Icons.Default.CleaningServices,
                            route = "cleaner",
                            accentColor = Color(0xFFEC4899)
                        ),
                        onClick = { onNavigate("cleaner") }
                    )
                    PowerToolRow(
                        tool = PowerTool(
                            title = "Batch File Renamer",
                            subtitle = "Bulk rename with prefixes, numbering, find & replace, casing",
                            icon = Icons.Default.DriveFileRenameOutline,
                            route = "batch_renamer",
                            badge = "New",
                            accentColor = Color(0xFF10B981)
                        ),
                        onClick = { onNavigate("batch_renamer") }
                    )
                    PowerToolRow(
                        tool = PowerTool(
                            title = "OCR Text Extractor",
                            subtitle = "Extract text from images, photos, documents & generate PDFs",
                            icon = Icons.Default.DocumentScanner,
                            route = "extractor",
                            accentColor = Color(0xFF6366F1)
                        ),
                        onClick = { onNavigate("extractor") }
                    )
                }
            }

            // Suite 2: Developer & Power Modding
            item {
                SectionHeader("⚡ Developer & Power Modding")
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PowerToolRow(
                        tool = PowerTool(
                            title = "Code & Script Editor",
                            subtitle = "Syntax diagnostics, auto-indent, gutter indicators & tabs",
                            icon = Icons.Default.Code,
                            route = "code_editor",
                            badge = "Dev",
                            accentColor = Color(0xFFF59E0B)
                        ),
                        onClick = { onNavigate("code_editor") }
                    )
                    PowerToolRow(
                        tool = PowerTool(
                            title = "Hex & Binary Inspector",
                            subtitle = "Raw byte viewer, offset address navigation, byte pattern search",
                            icon = Icons.Default.DataArray,
                            route = "hex_viewer",
                            badge = "Pro",
                            accentColor = Color(0xFF8B5CF6)
                        ),
                        onClick = { onNavigate("hex_viewer") }
                    )
                    PowerToolRow(
                        tool = PowerTool(
                            title = "Pro APK Inspector & Extractor",
                            subtitle = "Inspect signatures, manifest permissions, components & export APKs",
                            icon = Icons.Default.Android,
                            route = "apk_tools",
                            badge = "MT Style",
                            accentColor = Color(0xFF22C55E)
                        ),
                        onClick = { onNavigate("apk_tools") }
                    )
                    PowerToolRow(
                        tool = PowerTool(
                            title = "Shell Terminal",
                            subtitle = "Execute Android/Linux shell commands with optional SU root mode",
                            icon = Icons.Default.Terminal,
                            route = "terminal",
                            badge = "Root / Sh",
                            accentColor = Color(0xFF14B8A6)
                        ),
                        onClick = { onNavigate("terminal") }
                    )
                    PowerToolRow(
                        tool = PowerTool(
                            title = "Checksum & Hash Calculator",
                            subtitle = "Compute and verify MD5, SHA-1, SHA-256, SHA-512, and CRC32",
                            icon = Icons.Default.Calculate,
                            route = "hash_calc",
                            accentColor = Color(0xFF3B82F6)
                        ),
                        onClick = { onNavigate("hash_calc") }
                    )
                }
            }

            // Suite 3: Wireless Transfer & Network
            item {
                SectionHeader("🌐 Wireless Transfer & Network")
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PowerToolRow(
                        tool = PowerTool(
                            title = "Wi-Fi Web File Server",
                            subtitle = "Transfer files to/from PC, Mac, Linux through any web browser",
                            icon = Icons.Default.WifiTethering,
                            route = "web_share",
                            badge = "Wireless",
                            accentColor = Color(0xFFA855F7)
                        ),
                        onClick = { onNavigate("web_share") }
                    )
                    PowerToolRow(
                        tool = PowerTool(
                            title = "Network Inspector",
                            subtitle = "Wi-Fi link properties, DNS, gateway & LAN IP scanner",
                            icon = Icons.Default.NetworkWifi,
                            route = "network",
                            accentColor = Color(0xFF06B6D4)
                        ),
                        onClick = { onNavigate("network") }
                    )
                }
            }

            // Suite 4: Utilities & System
            item {
                SectionHeader("🛠️ Utilities & System")
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PowerToolRow(
                        tool = PowerTool(
                            title = "Notes & Code Snippets",
                            subtitle = "Full screen note editor with monospace switch & live stats",
                            icon = Icons.Default.Note,
                            route = "notes",
                            accentColor = Color(0xFFEAB308)
                        ),
                        onClick = { onNavigate("notes") }
                    )
                    PowerToolRow(
                        tool = PowerTool(
                            title = "Installed Applications",
                            subtitle = "Manage installed apps, system packages & launch info",
                            icon = Icons.Default.Apps,
                            route = "apps",
                            accentColor = Color(0xFF64748B)
                        ),
                        onClick = { onNavigate("apps") }
                    )
                    PowerToolRow(
                        tool = PowerTool(
                            title = "Contacts Backup",
                            subtitle = "Export and manage phone contacts safely",
                            icon = Icons.Default.Contacts,
                            route = "contacts",
                            accentColor = Color(0xFF0284C7)
                        ),
                        onClick = { onNavigate("contacts") }
                    )
                    PowerToolRow(
                        tool = PowerTool(
                            title = "Settings & Device Admin",
                            subtitle = "Security timeouts, biometric lock, uninstall protection",
                            icon = Icons.Default.Settings,
                            route = "settings",
                            accentColor = Color(0xFF94A3B8)
                        ),
                        onClick = { onNavigate("settings") }
                    )
                }
            }

            item {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        ),
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
fun CategoryChip(title: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = color
            )
        }
    }
}

@Composable
fun PowerToolRow(tool: PowerTool, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = tool.accentColor.copy(alpha = 0.15f),
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = tool.icon,
                        contentDescription = tool.title,
                        tint = tool.accentColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tool.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    if (tool.badge != null) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = CircleShape,
                            color = tool.accentColor.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = tool.badge,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = tool.accentColor
                                ),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = tool.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
