package com.example.ui.screens.apk

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitExtractionChoiceDialog(
    app: ApkItem,
    onDismiss: () -> Unit,
    onSelectMode: (ExtractionMode) -> Unit
) {
    val context = LocalContext.current
    val splitCount = (app.splitSourceDirs?.size ?: 0) + 1

    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = "Split APK Package Detected",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "${app.name} (${Formatter.formatShortFileSize(context, app.totalSize)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Info Card
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "This app is an Android App Bundle containing $splitCount components (Base APK + Architecture & Density splits).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = "Choose Output Format",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
            )

            // Option 1: Unified APKS Bundle (Recommended)
            ExtractionOptionCard(
                icon = Icons.Default.Archive,
                title = "Create App Bundle (.apks)",
                tag = "Recommended",
                tagColor = Color(0xFF10B981),
                description = "Preserves 100% original developer cryptographic signature. Fully installable via DevVault Session Installer and compatible with MT Manager & SAI.",
                buttonText = "Extract as .apks Bundle",
                onClick = {
                    onDismiss()
                    onSelectMode(ExtractionMode.UNIFIED_APKS_BUNDLE)
                }
            )

            // Option 2: Universal Standalone APK
            ExtractionOptionCard(
                icon = Icons.Default.Transform,
                title = "Create Universal APK (.apk)",
                tag = "Merged",
                tagColor = Color(0xFFF59E0B),
                description = "Intelligently merges Base APK with native libraries, assets, and split DEX files into a single unified .apk file.",
                buttonText = "Merge into Universal .apk",
                onClick = {
                    onDismiss()
                    onSelectMode(ExtractionMode.UNIVERSAL_STANDALONE_APK)
                }
            )

            // Option 3: Raw Splits
            ExtractionOptionCard(
                icon = Icons.Default.FolderZip,
                title = "Extract Raw Component Splits",
                tag = "Dev",
                tagColor = Color(0xFF8B5CF6),
                description = "Extracts separate base.apk and split_config.apk files for reverse engineering or inspection.",
                buttonText = "Extract Individual Splits",
                onClick = {
                    onDismiss()
                    onSelectMode(ExtractionMode.RAW_SPLITS_FOLDER)
                }
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ExtractionOptionCard(
    icon: ImageVector,
    title: String,
    tag: String,
    tagColor: Color,
    description: String,
    buttonText: String,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tagColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = tagColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = tagColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(buttonText, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
            }
        }
    }
}
