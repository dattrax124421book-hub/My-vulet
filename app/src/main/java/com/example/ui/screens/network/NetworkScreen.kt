package com.example.ui.screens.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.net.Inet4Address
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import java.net.InetAddress

data class ScannedDevice(val ip: String, val hostname: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val scope = rememberCoroutineScope()
    
    var networkType by remember { mutableStateOf("Unknown") }
    var isConnected by remember { mutableStateOf(false) }
    var ipAddress by remember { mutableStateOf("Unavailable") }
    var dnsServers by remember { mutableStateOf("Unavailable") }
    var gatewayAddress by remember { mutableStateOf("Unavailable") }
    var ssid by remember { mutableStateOf("Unavailable (Permission not granted / Not Wi-Fi)") }
    
    var scannedDevices by remember { mutableStateOf<List<ScannedDevice>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableStateOf(0) }
    var scanJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val linkProps: LinkProperties? = connectivityManager.getLinkProperties(network)
        
        if (capabilities != null) {
            isConnected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            networkType = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Other"
            }
        }
        
        if (linkProps != null) {
            val ipv4 = linkProps.linkAddresses.firstOrNull { it.address is Inet4Address }
            if (ipv4 != null) {
                ipAddress = "${ipv4.address.hostAddress}/${ipv4.prefixLength}"
            }
            
            if (linkProps.dnsServers.isNotEmpty()) {
                dnsServers = linkProps.dnsServers.joinToString(", ") { it.hostAddress ?: "" }
            }
            
            val defaultRoute = linkProps.routes.firstOrNull { it.isDefaultRoute }
            if (defaultRoute != null) {
                gatewayAddress = defaultRoute.gateway?.hostAddress ?: "Unavailable"
            }
        }
        
        if (networkType == "Wi-Fi") {
            try {
                val info = wifiManager.connectionInfo
                if (info != null && info.ssid != "<unknown ssid>") {
                    ssid = info.ssid
                }
            } catch (e: Exception) {
                // Ignore security exceptions if permissions are not fully granted
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Network Inspector") })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isConnected) Icons.Default.NetworkWifi else Icons.Default.WifiOff, 
                                contentDescription = null, 
                                tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Current Connection", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Status: ${if (isConnected) "Connected" else "Disconnected"}", style = MaterialTheme.typography.bodyLarge)
                        Text("Type: $networkType")
                        
                        if (isConnected) {
                            Divider(modifier = Modifier.padding(vertical = 8.dp))
                            Text("IP Address: $ipAddress", style = MaterialTheme.typography.bodyMedium)
                            Text("Gateway: $gatewayAddress", style = MaterialTheme.typography.bodyMedium)
                            Text("SSID: $ssid", style = MaterialTheme.typography.bodyMedium)
                            Text("DNS Servers: $dnsServers", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Physical distance cannot be reliably determined from this connection.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            
            if (isConnected && networkType == "Wi-Fi") {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("Local Device Discovery", style = MaterialTheme.typography.titleMedium)
                                Button(onClick = {
                                    if (isScanning && scanJob != null) {
                                        scanJob?.cancel()
                                        isScanning = false
                                    } else {
                                        isScanning = true
                                        scannedDevices = emptyList()
                                        scanProgress = 0
                                        scanJob = scope.launch(Dispatchers.IO) {
                                            val baseIp = ipAddress.substringBeforeLast(".") + "."
                                            val semaphore = Semaphore(32)
                                            var progress = 0
                                            
                                            try {
                                                coroutineScope {
                                                    for (i in 1..254) {
                                                        launch {
                                                            semaphore.withPermit {
                                                                try {
                                                                    val host = "$baseIp$i"
                                                                    val inetAddr = InetAddress.getByName(host)
                                                                    if (inetAddr.isReachable(500)) {
                                                                        val hostname = inetAddr.canonicalHostName
                                                                        val displayHost = if (hostname != host) hostname else null
                                                                        withContext(Dispatchers.Main) {
                                                                            scannedDevices = scannedDevices + ScannedDevice(host, displayHost)
                                                                        }
                                                                    }
                                                                } catch (e: Exception) {}
                                                                finally {
                                                                    withContext(Dispatchers.Main) {
                                                                        progress++
                                                                        scanProgress = progress
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            } catch (e: CancellationException) {
                                                // Cancelled
                                            } finally {
                                                withContext(Dispatchers.Main) {
                                                    isScanning = false
                                                    scanJob = null
                                                }
                                            }
                                        }
                                    }
                                }) {
                                    Text(if (isScanning) "Cancel Scan" else "Scan Network")
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            if (isScanning) {
                                Text("Scanned $scanProgress / 254", style = MaterialTheme.typography.bodyMedium)
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            if (scannedDevices.isNotEmpty()) {
                                scannedDevices.forEach { dev ->
                                    val namePart = if (dev.hostname != null) "${dev.hostname} (${dev.ip})" else dev.ip
                                    Text("Device found: $namePart", style = MaterialTheme.typography.bodyMedium)
                                    Divider()
                                }
                            } else if (!isScanning && scannedDevices.isEmpty()) {
                                Text("No other devices found or scan not run.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
