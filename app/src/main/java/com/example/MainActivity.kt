package com.example

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.PairingCodeEntryCard
import com.example.ui.PairingDialogActivity
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
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
    var lastSubmittedCode by remember { mutableStateOf<String?>(null) }
    var showInlineCard by remember { mutableStateOf(true) }

    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "ADB Pairing Code Entry",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.testTag("app_title")
        )

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
            Button(
                onClick = { showInlineCard = true },
                modifier = Modifier.testTag("show_pairing_card_button")
            ) {
                Text("Show Pairing UI")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                PairingDialogActivity.onPairingCodeSubmittedCallback = { code ->
                    Log.i("MainActivity", "Pairing code received via Dialog Activity: $code")
                    lastSubmittedCode = code
                }
                context.startActivity(PairingDialogActivity.createIntent(context))
            },
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .testTag("launch_dialog_button")
        ) {
            Text("Launch Floating Dialog Activity")
        }

        Spacer(modifier = Modifier.height(24.dp))

        lastSubmittedCode?.let { code ->
            Text(
                text = "Last Code Submitted: $code",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("submitted_code_text")
            )
        }
    }
}
