package com.example.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MyApplicationTheme

/**
 * A floating/dialog activity that displays the 6-digit wireless pairing code entry dialog.
 * Can be launched over the Android Settings screen or from notifications/main UI.
 */
class PairingDialogActivity : ComponentActivity() {

    companion object {
        private const val TAG = "PairingDialogActivity"
        const val EXTRA_PREFILLED_CODE = "extra_prefilled_code"
        const val ACTION_PAIRING_CODE_SUBMITTED = "com.example.action.PAIRING_CODE_SUBMITTED"
        const val EXTRA_SUBMITTED_CODE = "extra_submitted_code"

        /**
         * Internal callback holder for in-process pairing code submission
         */
        var onPairingCodeSubmittedCallback: ((code: String) -> Unit)? = null

        fun createIntent(context: Context, prefilledCode: String? = null): Intent {
            return Intent(context, PairingDialogActivity::class.java).apply {
                if (prefilledCode != null) {
                    putExtra(EXTRA_PREFILLED_CODE, prefilledCode)
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefilledCode = intent.getStringExtra(EXTRA_PREFILLED_CODE) ?: ""

        setContent {
            MyApplicationTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    PairingCodeEntryCard(
                        modifier = Modifier
                            .widthIn(max = 420.dp)
                            .padding(16.dp),
                        initialCode = prefilledCode,
                        onPairSubmit = { code ->
                            handlePairCodeSubmitted(code)
                        },
                        onDismiss = {
                            Log.d(TAG, "Pairing dialog dismissed by user")
                            finish()
                        }
                    )
                }
            }
        }
    }

    private fun handlePairCodeSubmitted(code: String) {
        Log.i(TAG, "Pairing code submitted: $code")
        
        // Trigger internal callback
        onPairingCodeSubmittedCallback?.invoke(code)

        // Set result for caller if launched via startActivityForResult
        val resultIntent = Intent().apply {
            putExtra(EXTRA_SUBMITTED_CODE, code)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }
}
