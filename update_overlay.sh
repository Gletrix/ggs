#!/bin/bash
cat << 'INNEREOF' > app/src/main/java/com/example/ui/AdbOverlayService.kt
package com.example.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.MainActivity
import com.example.ui.theme.MyApplicationTheme
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Foreground Service that displays a compact, moveable ADB Pairing UI as a system-level overlay
 * using WindowManager and Jetpack Compose.
 */
class AdbOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    companion object {
        private const val TAG = "AdbOverlayService"
        const val CHANNEL_ID = "adb_pairing_overlay_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_OVERLAY = "com.example.action.START_OVERLAY"
        const val ACTION_STOP_OVERLAY = "com.example.action.STOP_OVERLAY"
        const val EXTRA_PREFILLED_CODE = "extra_prefilled_code"

        const val ACTION_PAIRING_CODE_SUBMITTED = "com.example.action.PAIRING_CODE_SUBMITTED"
        const val EXTRA_SUBMITTED_CODE = "extra_submitted_code"

        var isRunning: Boolean = false
            private set

        /**
         * Global callback for in-process pairing code notification.
         */
        var onPairingCodeSubmittedCallback: ((String) -> Unit)? = null
        var onOverlayDismissedCallback: (() -> Unit)? = null

        fun start(context: Context, prefilledCode: String? = null) {
            val intent = Intent(context, AdbOverlayService::class.java).apply {
                action = ACTION_START_OVERLAY
                if (prefilledCode != null) {
                    putExtra(EXTRA_PREFILLED_CODE, prefilledCode)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AdbOverlayService::class.java).apply {
                action = ACTION_STOP_OVERLAY
            }
            context.startService(intent)
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayComposeView: ComposeView? = null
    private var currentLayoutParams: WindowManager.LayoutParams? = null
    private var isOverlayAttached = false

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val appViewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = appViewModelStore

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_OVERLAY) {
            Log.d(TAG, "Stop overlay action received.")
            stopOverlayAndSelf()
            return START_NOT_STICKY
        }

        val prefilledCode = intent?.getStringExtra(EXTRA_PREFILLED_CODE) ?: ""
        showOverlay(prefilledCode)

        return START_NOT_STICKY
    }

    private fun showOverlay(prefilledCode: String) {
        if (isOverlayAttached && overlayComposeView != null) {
            Log.d(TAG, "Overlay is already attached.")
            return
        }

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val displayMetrics = resources.displayMetrics
            val cardEstimatedWidthPx = (320 * displayMetrics.density).toInt()
            val initialX = ((displayMetrics.widthPixels - cardEstimatedWidthPx) / 2).coerceAtLeast(20)
            val initialY = (displayMetrics.heightPixels * 0.12).toInt()

            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = initialX
                y = initialY
            }

            currentLayoutParams = layoutParams

            val composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(this@AdbOverlayService)
                setViewTreeSavedStateRegistryOwner(this@AdbOverlayService)
                setViewTreeViewModelStoreOwner(this@AdbOverlayService)

                setContent {
                    MyApplicationTheme {
                        PairingCodeEntryCard(
                            modifier = Modifier.widthIn(min = 290.dp, max = 330.dp),
                            initialCode = prefilledCode,
                            title = "Pairing Code",
                            description = "Enter 6-digit PIN from Settings",
                            onDrag = { dx, dy ->
                                updateOverlayPosition(dx, dy)
                            },
                            onPairSubmit = { code ->
                                handlePairingCodeSubmitted(code)
                            },
                            onDismiss = {
                                handleOverlayDismissed()
                            },
                            onFocusChanged = { isFocused ->
                                updateFocusState(isFocused)
                            }
                        )
                    }
                }
            }

            overlayComposeView = composeView
            windowManager?.addView(composeView, layoutParams)
            isOverlayAttached = true
            Log.i(TAG, "Successfully added moveable ADB pairing overlay view to WindowManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach overlay view to WindowManager", e)
            stopOverlayAndSelf()
        }
    }

    private fun updateFocusState(isFocused: Boolean) {
        val lp = currentLayoutParams ?: return
        if (isFocused) {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        try {
            if (isOverlayAttached && overlayComposeView != null) {
                windowManager?.updateViewLayout(overlayComposeView, lp)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error updating overlay focus state: ${e.message}")
        }
    }

    private fun updateOverlayPosition(dx: Float, dy: Float) {
        val lp = currentLayoutParams ?: return
        val dm = resources.displayMetrics
        val maxX = dm.widthPixels - 100
        val maxY = dm.heightPixels - 100

        lp.x = (lp.x + dx.toInt()).coerceIn(0, maxX)
        lp.y = (lp.y + dy.toInt()).coerceIn(0, maxY)

        try {
            if (isOverlayAttached && overlayComposeView != null) {
                windowManager?.updateViewLayout(overlayComposeView, lp)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error moving overlay: ${e.message}")
        }
    }

    private fun handlePairingCodeSubmitted(code: String) {
        Log.i(TAG, "Pairing code submitted from overlay: \$code")
        onPairingCodeSubmittedCallback?.invoke(code)

        val broadcastIntent = Intent(ACTION_PAIRING_CODE_SUBMITTED).apply {
            putExtra(EXTRA_SUBMITTED_CODE, code)
            setPackage(packageName)
        }
        sendBroadcast(broadcastIntent)
        stopOverlayAndSelf()
    }

    private fun handleOverlayDismissed() {
        Log.d(TAG, "Overlay dismissed by user")
        onOverlayDismissedCallback?.invoke()
        stopOverlayAndSelf()
    }

    private fun stopOverlayAndSelf() {
        removeOverlayView()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun removeOverlayView() {
        if (isOverlayAttached && overlayComposeView != null) {
            try {
                windowManager?.removeView(overlayComposeView)
                Log.d(TAG, "Removed overlay view from WindowManager")
            } catch (e: Exception) {
                Log.w(TAG, "Error removing overlay view: \${e.message}")
            }
            overlayComposeView = null
            currentLayoutParams = null
            isOverlayAttached = false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ADB Pairing Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows floating pairing code entry over Settings"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val stopIntent = Intent(this, AdbOverlayService::class.java).apply {
            action = ACTION_STOP_OVERLAY
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wireless Pairing Overlay Active")
            .setContentText("Enter the 6-digit pairing code over Settings")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close Overlay", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        removeOverlayView()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        appViewModelStore.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
INNEREOF
chmod +x update_overlay.sh
./update_overlay.sh