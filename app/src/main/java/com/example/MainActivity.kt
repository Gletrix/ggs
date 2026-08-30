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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.AdbOverlayService
import com.example.ui.PairingCodeEntryCard
import com.example.ui.PairingDialogActivity
import com.example.ui.theme.MyApplicationTheme

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

    var hasOverlayPermission by remember { mutableStateOf(MainActivity.checkOverlayPermission(context)) }
    var lastSubmittedCode by remember { mutableStateOf<String?>(null) }
    var showInlineCard by remember { mutableStateOf(false) }

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

    // Set callback on overlay service
    DisposableEffect(Unit) {
        AdbOverlayService.onPairingCodeSubmittedCallback = { code ->
            Log.i("MainActivity", "Pairing code received via System Overlay: $code")
            lastSubmittedCode = code
        }
        PairingDialogActivity.onPairingCodeSubmittedCallback = { code ->
            Log.i("MainActivity", "Pairing code received via Dialog Activity: $code")
            lastSubmittedCode = code
        }
        onDispose {
            AdbOverlayService.onPairingCodeSubmittedCallback = null
            PairingDialogActivity.onPairingCodeSubmittedCallback = null
        }
    }

    Column(
        modifier = modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "ADB Wireless Pairing",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.testTag("app_title")
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Overlay Permission Status Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .testTag("permission_status_card"),
            colors = CardDefaults.cardColors(
                containerColor = if (hasOverlayPermission) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                }
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (hasOverlayPermission) "Overlay Permission: Granted" else "Overlay Permission: Required",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (hasOverlayPermission) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (hasOverlayPermission) {
                        "System overlay is ready to float over Wireless debugging settings."
                    } else {
                        "Required to display the pairing PIN floating over Settings > Developer options."
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

        Spacer(modifier = Modifier.height(16.dp))

        if (showInlineCard) {
            PairingCodeEntryCard(
                onPairSubmit = { code ->
                    Log.i("MainActivity", "Pairing code submitted from inline UI: $code")
                    lastSubmittedCode = code
                },
                onDismiss = {
                    showInlineCard = false
                }
            )
        } else {
            // Button to Launch System Overlay
            Button(
                onClick = {
                    if (MainActivity.checkOverlayPermission(context)) {
                        AdbOverlayService.start(context)
                        MainActivity.openDeveloperSettings(context)
                    } else {
                        MainActivity.requestOverlayPermission(context)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .testTag("launch_overlay_button")
            ) {
                Text(if (hasOverlayPermission) "Launch Floating Overlay" else "Grant Overlay Permission")
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Open Developer Settings directly
            FilledTonalButton(
                onClick = {
                    MainActivity.openDeveloperSettings(context)
                },
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .testTag("open_developer_settings_button")
            ) {
                Text("Open Developer Options")
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Show Inline Card option
            OutlinedButton(
                onClick = { showInlineCard = true },
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .testTag("show_pairing_card_button")
            ) {
                Text("Show Inline Pairing UI")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        lastSubmittedCode?.let { code ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("submitted_code_card"),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Last Code Submitted",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = code,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("submitted_code_text")
                    )
                }
            }
        }
    }
}
