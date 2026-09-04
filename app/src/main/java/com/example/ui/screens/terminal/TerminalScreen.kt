package com.example.ui.screens.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

data class TerminalLog(
    val isCommand: Boolean,
    val text: String,
    val isError: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var commandInput by remember { mutableStateOf("") }
    val logs = remember { mutableStateListOf<TerminalLog>() }
    var isRunningCommand by remember { mutableStateOf(false) }
    var useRoot by remember { mutableStateOf(false) }

    // Check if device has SU binary
    val hasRoot = remember {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        paths.any { File(it).exists() }
    }

    LaunchedEffect(Unit) {
        logs.add(TerminalLog(false, "=== DevVault Linux & Android Shell Console ==="))
        logs.add(TerminalLog(false, "OS: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"))
        logs.add(TerminalLog(false, "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"))
        logs.add(TerminalLog(false, "Root Binary: ${if (hasRoot) "DETECTED (su available)" else "Not detected (Standard sh mode)"}"))
        logs.add(TerminalLog(false, "Type any command or use quick actions below.\n"))
    }

    fun executeCommand(cmdStr: String) {
        val cmd = cmdStr.trim()
        if (cmd.isEmpty()) return

        if (cmd.equals("clear", ignoreCase = true)) {
            logs.clear()
            commandInput = ""
            return
        }

        logs.add(TerminalLog(true, "${if (useRoot) "#" else "$"} $cmd"))
        commandInput = ""
        isRunningCommand = true

        scope.launch(Dispatchers.IO) {
            try {
                val shell = if (useRoot) "su" else "sh"
                val process = ProcessBuilder(shell, "-c", cmd)
                    .redirectErrorStream(true)
                    .start()

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                val outputLines = mutableListOf<String>()

                while (reader.readLine().also { line = it } != null) {
                    outputLines.add(line!!)
                }

                val exitCode = process.waitFor()

                withContext(Dispatchers.Main) {
                    if (outputLines.isEmpty()) {
                        logs.add(TerminalLog(false, "[Command returned 0 with no output]"))
                    } else {
                        outputLines.forEach { outLine ->
                            logs.add(TerminalLog(false, outLine))
                        }
                    }
                    if (exitCode != 0) {
                        logs.add(TerminalLog(false, "Process exited with code: $exitCode", isError = true))
                    }
                    isRunningCommand = false
                    listState.animateScrollToItem((logs.size - 1).coerceAtLeast(0))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    logs.add(TerminalLog(false, "Execution Error: ${e.message}", isError = true))
                    isRunningCommand = false
                    listState.animateScrollToItem((logs.size - 1).coerceAtLeast(0))
                }
            }
        }
    }

    val quickCommands = listOf(
        "df -h",
        "uname -a",
        "ps -A",
        "getprop ro.build.version.release",
        "cat /proc/meminfo",
        "cat /proc/cpuinfo",
        "ip addr",
        "pm list packages -3",
        "dumpsys battery"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Shell Terminal")
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = CircleShape,
                            color = if (useRoot) Color(0xFFDC2626) else MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = if (useRoot) "ROOT" else "USER",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = if (useRoot) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val text = logs.joinToString("\n") { it.text }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Terminal Log", text))
                        Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy All")
                    }
                    IconButton(onClick = { logs.clear() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF0F172A)) // Deep terminal dark canvas
        ) {
            // Quick Shortcuts Row
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E293B))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (hasRoot) {
                    item {
                        FilterChip(
                            selected = useRoot,
                            onClick = { useRoot = !useRoot },
                            label = { Text(if (useRoot) "SU Mode ON" else "Enable SU") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFDC2626),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
                items(quickCommands) { cmd ->
                    SuggestionChip(
                        onClick = { executeCommand(cmd) },
                        label = {
                            Text(
                                cmd,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = Color(0xFF38BDF8)
                            )
                        }
                    )
                }
            }

            // Terminal Console Output
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                items(logs) { log ->
                    Text(
                        text = log.text,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp
                        ),
                        color = when {
                            log.isCommand -> Color(0xFF4ADE80) // Green command prompt
                            log.isError -> Color(0xFFF87171)   // Red error
                            else -> Color(0xFFE2E8F0)          // Soft white terminal text
                        }
                    )
                }
            }

            // Command Input Bar
            Surface(
                color = Color(0xFF1E293B),
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (useRoot) "# " else "$ ",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (useRoot) Color(0xFFEF4444) else Color(0xFF22C55E)
                        )
                    )

                    OutlinedTextField(
                        value = commandInput,
                        onValueChange = { commandInput = it },
                        placeholder = { Text("enter command (e.g. ls -la)", color = Color(0xFF64748B)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color(0xFF38BDF8),
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF475569)
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { executeCommand(commandInput) })
                    )

                    Spacer(Modifier.width(8.dp))

                    IconButton(
                        onClick = { executeCommand(commandInput) },
                        enabled = commandInput.isNotEmpty() && !isRunningCommand,
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = Color(0xFF38BDF8)
                        )
                    ) {
                        if (isRunningCommand) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF38BDF8))
                        } else {
                            Icon(Icons.Default.Send, contentDescription = "Run")
                        }
                    }
                }
            }
        }
    }
}
