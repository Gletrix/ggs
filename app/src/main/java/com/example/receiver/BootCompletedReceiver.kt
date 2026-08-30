package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.adb.AdbAutoReconnectManager

/**
 * BroadcastReceiver listening for boot completion and network reconnect events.
 * Triggers background mDNS connect discovery and authentication when keys are present.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Received broadcast action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val reconnectManager = AdbAutoReconnectManager(context.applicationContext)
            reconnectManager.startAutoReconnect()
        }
    }
}
