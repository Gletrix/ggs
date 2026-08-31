package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.adb.AdbKeyStorageManager
import com.example.adb.AdbMdnsDiscoveryManager
import com.example.adb.AdbPairingManager
import com.example.adb.AdbServiceType
import com.example.adb.AdbSessionManager
import com.example.adb.DiscoveredAdbService
import com.example.adb.WmSizeResult
import com.example.ui.AdbOverlayService
import com.example.ui.PairingCodeEntryCard
import com.example.ui.PairingDialogActivity
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import com.example.ui.GameSelector
import androidx.compose.material3.Switch
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"

        /**
         * Checks if SYSTEM_ALERT_WINDOW permission is granted.
         */
        fun checkOverlayPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }

        /**
         * Launches the system settings screen to grant overlay permission.
         */
        fun requestOverlayPermission(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to launch package-specific overlay settings, falling back", e)
                    val genericIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(genericIntent)
                }
            }
        }

        /**
         * Opens the device Developer Options settings screen.
         */
        fun openDeveloperSettings(context: Context) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open developer settings", e)
                Toast.makeText(context, "Could not open Developer Options", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PairingDemoScreen(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
fun PairingDemoScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val keyStorageManager = remember { AdbKeyStorageManager(context) }
    val mdnsDiscoveryManager = remember { AdbMdnsDiscoveryManager(context) }
    val pairingManager = remember { AdbPairingManager(keyStorageManager) }
    val sessionManager = remember { AdbSessionManager(keyStorageManager) }

    var hasOverlayPermission by remember { mutableStateOf(MainActivity.checkOverlayPermission(context)) }
    var lastSubmittedCode by remember { mutableStateOf<String?>(null) }
    var showInlineCard by remember { mutableStateOf(false) }

    var isExecuting by remember { mutableStateOf(false) }
    var currentStep by remember { mutableStateOf("Ready") }
    var executionSuccess by remember { mutableStateOf<Boolean?>(null) }
    var finalResult by remember { mutableStateOf<WmSizeResult?>(null) }
    val logs = remember { mutableStateListOf<String>() }

    var isConsoleModeOn by remember { mutableStateOf(false) }
    var selectedGamePackage by remember { mutableStateOf<String?>(null) }
    var installedGames by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            val games = resolveInfos.map {
                it.loadLabel(pm).toString() to it.activityInfo.packageName
            }.distinctBy { it.second }.sortedBy { it.first }
            withContext(Dispatchers.Main) {
                installedGames = games
                if (games.isNotEmpty()) {
                    selectedGamePackage = games[0].second
                }
            }
        }
    }

    fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs.add("[$time] $message")
        Log.i("MainActivity", "[$time] $message")
    }

    fun toggleConsoleMode(enable: Boolean, selectedPackage: String?) {
        if (enable && selectedPackage == null) {
            Toast.makeText(context, "Select a game first", Toast.LENGTH_SHORT).show()
            return
        }
        
        isExecuting = true
        executionSuccess = null
        finalResult = null
        logs.clear()
        
        coroutineScope.launch(Dispatchers.IO) {
            try {
                if (enable) {
                    addLog("Enabling Console Mode for $selectedPackage...")
                    withContext(Dispatchers.Main) { currentStep = "Enabling Console Mode..." }
                } else {
                    addLog("Disabling Console Mode...")
                    withContext(Dispatchers.Main) { currentStep = "Disabling Console Mode..." }
                }

                // Locate connect service
                mdnsDiscoveryManager.startDiscovery(discoverPairing = false, discoverConnect = true)
                var connectService = mdnsDiscoveryManager.discoveryState.value.connectServices.firstOrNull()
                if (connectService == null) {
                    connectService = withTimeoutOrNull(10000L) {
                        mdnsDiscoveryManager.serviceEvents.first { it.serviceType == AdbServiceType.CONNECT }
                    }
                }
                
                if (connectService == null) {
                    withContext(Dispatchers.Main) {
                        currentStep = "Failed: mDNS connect service discovery timeout"
                        isExecuting = false
                        executionSuccess = false
                    }
                    return@launch
                }
                
                val connectHost = connectService.hostAddress ?: connectService.host?.hostAddress ?: "127.0.0.1"
                val connectPort = connectService.port
                
                val connResult = sessionManager.connect(connectHost, connectPort)
                if (connResult.isFailure) {
                    withContext(Dispatchers.Main) {
                        currentStep = "Failed: ADB connection error"
                        isExecuting = false
                        executionSuccess = false
                    }
                    return@launch
                }
                
                if (enable) {
                    val result = sessionManager.enableConsoleMode(connectHost, connectPort, selectedPackage!!)
                    if (result.isSuccess) {
                        withContext(Dispatchers.Main) {
                            isConsoleModeOn = true
                            currentStep = "Console Mode Enabled"
                            executionSuccess = true

                        }
                        addLog("✅ Console Mode enabled successfully.")
                    } else {
                        throw result.exceptionOrNull()!!
                    }
                } else {
                    val result = sessionManager.disableConsoleMode(connectHost, connectPort)
                    if (result.isSuccess) {
                        withContext(Dispatchers.Main) {
                            isConsoleModeOn = false
                            currentStep = "Console Mode Disabled"
                            executionSuccess = true

                        }
                        addLog("✅ Console Mode disabled successfully.")
                    } else {
                        throw result.exceptionOrNull()!!
                    }
                }
            } catch (e: Exception) {
                addLog("❌ Unexpected error: ${e.localizedMessage}")
                withContext(Dispatchers.Main) {
                    currentStep = "Failed: Exception"
                    isExecuting = false
                    executionSuccess = false
                    if (!enable) {
                        isConsoleModeOn = false // Reset state on error to allow retry

                    }
                }
            } finally {
                withContext(Dispatchers.Main) { isExecuting = false }
            }
        }
    }

    fun executePairingFlow(code: String) {
        lastSubmittedCode = code
        isExecuting = true
        executionSuccess = null
        finalResult = null
        logs.clear()

        coroutineScope.launch(Dispatchers.IO) {
            try {
                addLog("Starting flow with pairing code: $code")

                // Step 1: Discovering ADB services via mDNS...
                withContext(Dispatchers.Main) {
                    currentStep = "Discovering ADB services via mDNS..."
                }
                addLog("Discovering ADB services via mDNS...")
                mdnsDiscoveryManager.clearDiscoveredServices()
                mdnsDiscoveryManager.startDiscovery(discoverPairing = true, discoverConnect = true)

                // Locate pairing service
                var pairingService: DiscoveredAdbService? = null
                val initialPairing = mdnsDiscoveryManager.discoveryState.value.pairingServices.firstOrNull()
                if (initialPairing != null) {
                    pairingService = initialPairing
                } else {
                    pairingService = withTimeoutOrNull(20000L) {
                        mdnsDiscoveryManager.serviceEvents.first { it.serviceType == AdbServiceType.PAIRING }
                    }
                }

                if (pairingService == null) {
                    val err = "No _adb-tls-pairing service discovered. Ensure 'Pair device with pairing code' dialog is open on screen."
                    addLog("❌ $err")
                    withContext(Dispatchers.Main) {
                        currentStep = "Failed: mDNS pairing service discovery timeout"
                        isExecuting = false
                        executionSuccess = false
                    }
                    mdnsDiscoveryManager.stopDiscovery()
                    return@launch
                }

                val pairHost = pairingService.hostAddress ?: pairingService.host?.hostAddress ?: "127.0.0.1"
                val pairPort = pairingService.port
                addLog("Found pairing service at $pairHost:$pairPort")

                // Step 2: Pairing with code...
                withContext(Dispatchers.Main) {
                    currentStep = "Pairing with code..."
                }
                addLog("Pairing with code on $pairHost:$pairPort...")

                val pairResult = pairingManager.pair(pairHost, pairPort, code)
                if (pairResult.isFailure) {
                    val err = pairResult.exceptionOrNull()?.message ?: "Pairing handshake failed"
                    addLog("❌ $err")
                    withContext(Dispatchers.Main) {
                        currentStep = "Failed: Pairing error"
                        isExecuting = false
                        executionSuccess = false
                    }
                    mdnsDiscoveryManager.stopDiscovery()
                    return@launch
                }

                addLog("✅ Pairing successful with $pairHost:$pairPort")

                // Step 3: Connecting to ADB session...
                withContext(Dispatchers.Main) {
                    currentStep = "Connecting to ADB session..."
                }
                addLog("Connecting to ADB session...")

                var connectService: DiscoveredAdbService? = mdnsDiscoveryManager.discoveryState.value.connectServices.firstOrNull()
                if (connectService == null) {
                    connectService = withTimeoutOrNull(20000L) {
                        mdnsDiscoveryManager.serviceEvents.first { it.serviceType == AdbServiceType.CONNECT }
                    }
                }

                if (connectService == null) {
                    val err = "No _adb-tls-connect service discovered. Ensure Wireless Debugging is active."
                    addLog("❌ $err")
                    withContext(Dispatchers.Main) {
                        currentStep = "Failed: mDNS connect service discovery timeout"
                        isExecuting = false
                        executionSuccess = false
                    }
                    mdnsDiscoveryManager.stopDiscovery()
                    return@launch
                }

                val connectHost = connectService.hostAddress ?: connectService.host?.hostAddress ?: "127.0.0.1"
                val connectPort = connectService.port
                addLog("Found connect service at $connectHost:$connectPort")

                val connResult = sessionManager.connect(connectHost, connectPort)
                if (connResult.isFailure) {
                    val err = connResult.exceptionOrNull()?.message ?: "ADB connection failed"
                    addLog("❌ $err")
                    withContext(Dispatchers.Main) {
                        currentStep = "Failed: Connection error"
                        isExecuting = false
                        executionSuccess = false
                    }
                    mdnsDiscoveryManager.stopDiscovery()
                    return@launch
                }

                addLog("✅ Authenticated TLS ADB session established")
                
                withContext(Dispatchers.Main) {
                    currentStep = "Paired and Connected Successfully"
                    isExecuting = false
                    executionSuccess = true
                }
                mdnsDiscoveryManager.stopDiscovery()
            } catch (e: Exception) {
                addLog("❌ Unexpected error: ${e.localizedMessage ?: e.javaClass.simpleName}")
                withContext(Dispatchers.Main) {
                    currentStep = "Failed: Exception"
                    isExecuting = false
                    executionSuccess = false
                }
                mdnsDiscoveryManager.stopDiscovery()
            }
        }
    }

    // Re-check overlay permission whenever activity resumes
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = MainActivity.checkOverlayPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Set callback on overlay service & dialog activity
    DisposableEffect(Unit) {
        AdbOverlayService.onPairingCodeSubmittedCallback = { code ->
            Log.i("MainActivity", "Pairing code received via System Overlay: $code")
            executePairingFlow(code)
        }
        PairingDialogActivity.onPairingCodeSubmittedCallback = { code ->
            Log.i("MainActivity", "Pairing code received via Dialog Activity: $code")
            executePairingFlow(code)
        }

        onDispose {
            AdbOverlayService.onPairingCodeSubmittedCallback = null
            PairingDialogActivity.onPairingCodeSubmittedCallback = null
            mdnsDiscoveryManager.stopDiscovery()
            sessionManager.close()
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "ADB Wireless Pairing & Screen Resizer",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("app_title")
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Overlay Permission Status Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .testTag("permission_status_card"),
            colors = CardDefaults.cardColors(
                containerColor = if (hasOverlayPermission) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                }
            )
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (hasOverlayPermission) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (hasOverlayPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (hasOverlayPermission) "Overlay Permission: Granted" else "Overlay Permission: Required",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (hasOverlayPermission) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (hasOverlayPermission) {
                        "Floating overlay is ready to enter PIN over Settings > Wireless debugging."
                    } else {
                        "Grant overlay permission so the pairing card floats over Developer Options."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasOverlayPermission) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (showInlineCard) {
            PairingCodeEntryCard(
                onPairSubmit = { code ->
                    showInlineCard = false
                    executePairingFlow(code)
                },
                onDismiss = {
                    showInlineCard = false
                }
            )
        } else {
            // Action Buttons
            Button(
                onClick = {
                    if (MainActivity.checkOverlayPermission(context)) {
                        AdbOverlayService.start(context)
                        MainActivity.openDeveloperSettings(context)
                    } else {
                        MainActivity.requestOverlayPermission(context)
                    }
                },
                enabled = !isExecuting,
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .testTag("launch_overlay_button")
            ) {
                Text(if (hasOverlayPermission) "Launch Floating Overlay" else "Grant Overlay Permission")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(0.9f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        MainActivity.openDeveloperSettings(context)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_developer_settings_button")
                ) {
                    Text("Developer Options", style = MaterialTheme.typography.labelMedium)
                }

                OutlinedButton(
                    onClick = { showInlineCard = true },
                    enabled = !isExecuting,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("show_pairing_card_button")
                ) {
                    Text("Inline PIN Card", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Console Mode Controls
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Gaming Console Mode",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                
                GameSelector(
                    installedGames = installedGames,
                    selectedGamePackage = selectedGamePackage,
                    onGameSelected = { selectedGamePackage = it }
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = {
                        selectedGamePackage?.let { pkg ->
                            context.packageManager.getLaunchIntentForPackage(pkg)?.let { intent ->
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } ?: run {
                                Toast.makeText(context, "Cannot launch app", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedGamePackage != null
                ) {
                    Text("LAUNCH GAME")
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("CONSOLE MODE", style = MaterialTheme.typography.labelLarge)
                    Switch(
                        checked = isConsoleModeOn,
                        onCheckedChange = { enable ->
                            toggleConsoleMode(enable, selectedGamePackage)
                        },
                        enabled = !isExecuting
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Real-Time Progress / Status Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("status_indicator_card"),
            colors = CardDefaults.cardColors(
                containerColor = when (executionSuccess) {
                    true -> MaterialTheme.colorScheme.tertiaryContainer
                    false -> MaterialTheme.colorScheme.errorContainer
                    null -> MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Current Status",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isExecuting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = currentStep,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = when (executionSuccess) {
                        true -> MaterialTheme.colorScheme.onTertiaryContainer
                        false -> MaterialTheme.colorScheme.onErrorContainer
                        null -> MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.testTag("current_step_text")
                )

                if (isExecuting) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .testTag("progress_indicator")
                    )
                }

                lastSubmittedCode?.let { code ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Submitted PIN: $code",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("submitted_code_text")
                    )
                }
            }
        }

        // Final Result Card (wm size verification)
        finalResult?.let { res ->
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("final_result_card"),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Command Verified Successfully",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Executed: ${res.executedCommand}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Verification: ${res.verificationOutput}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Real-Time Log Console
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("logs_console_card"),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
            ),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Execution Logs",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (logs.isNotEmpty()) {
                        Text(
                            text = "${logs.size} entries",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(10.dp)
                ) {
                    if (logs.isEmpty()) {
                        Text(
                            text = "No logs yet. Launch overlay or submit PIN to begin.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            logs.forEach { logLine ->
                                Text(
                                    text = logLine,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    ),
                                    color = if (logLine.contains("❌")) {
                                        MaterialTheme.colorScheme.error
                                    } else if (logLine.contains("✅")) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
