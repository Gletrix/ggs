package com.example.adb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * State representing post-reboot / auto-reconnection lifecycle.
 */
sealed interface AutoReconnectState {
    data object Idle : AutoReconnectState
    data object CheckingKeys : AutoReconnectState
    data object PairingRequired : AutoReconnectState
    data object DiscoveringConnectService : AutoReconnectState
    data class Connecting(val host: String, val port: Int) : AutoReconnectState
    data class Connected(val host: String, val port: Int) : AutoReconnectState
    data class Error(val message: String, val cause: Throwable? = null) : AutoReconnectState
}

/**
 * Orchestrates seamless reconnect on app launch, boot, or Wi-Fi reconnect:
 * 1. Checks if an authenticated RSA keypair exists in AdbKeyStorageManager.
 * 2. If present, bypasses pairing and starts mDNS discovery for `_adb-tls-connect._tcp`.
 * 3. As soon as the daemon's connect port is discovered, connects automatically via AdbSessionManager.
 */
class AdbAutoReconnectManager(
    context: Context,
    private val keyStorageManager: AdbKeyStorageManager = AdbKeyStorageManager(context),
    private val mdnsDiscoveryManager: AdbMdnsDiscoveryManager = AdbMdnsDiscoveryManager(context),
    private val sessionManager: AdbSessionManager = AdbSessionManager(keyStorageManager),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : Closeable {

    companion object {
        private const val TAG = "AdbAutoReconnectManager"
    }

    private val _state = MutableStateFlow<AutoReconnectState>(AutoReconnectState.Idle)
    val state: StateFlow<AutoReconnectState> = _state.asStateFlow()

    private var observationJob: Job? = null
    private var isRunning = false

    /**
     * Initiates auto-discovery and reconnection pipeline asynchronously.
     */
    @Synchronized
    fun startAutoReconnect(autoExecuteOnConnect: (suspend (sessionManager: AdbSessionManager, host: String, port: Int) -> Unit)? = null) {
        if (isRunning) {
            Log.d(TAG, "Auto-reconnect is already active.")
            return
        }
        isRunning = true

        observationJob?.cancel()
        observationJob = scope.launch {
            runAutoReconnectPipeline(autoExecuteOnConnect)
        }
    }

    /**
     * Executes the reconnect pipeline suspending inline.
     */
    suspend fun runAutoReconnectPipeline(
        autoExecuteOnConnect: (suspend (sessionManager: AdbSessionManager, host: String, port: Int) -> Unit)? = null
    ) {
        _state.value = AutoReconnectState.CheckingKeys

        val hasKeys = keyStorageManager.hasKeys()
        if (!hasKeys) {
            Log.i(TAG, "No existing ADB keypair found. Pairing is required.")
            _state.value = AutoReconnectState.PairingRequired
            return
        }

        Log.i(TAG, "Existing ADB keypair found. Starting connect mDNS discovery...")
        _state.value = AutoReconnectState.DiscoveringConnectService

        // Start discovering only connect service (_adb-tls-connect._tcp)
        mdnsDiscoveryManager.startDiscovery(discoverPairing = false, discoverConnect = true)

        // Listen to discovered service events
        mdnsDiscoveryManager.serviceEvents.collectLatest { service ->
            if (service.serviceType == AdbServiceType.CONNECT) {
                val host = service.hostAddress ?: service.host?.hostAddress ?: "127.0.0.1"
                val port = service.port

                Log.i(TAG, "Discovered active connect service at $host:$port. Attempting TLS connection...")
                _state.value = AutoReconnectState.Connecting(host, port)

                val result = sessionManager.connect(host, port)
                if (result.isSuccess) {
                    Log.i(TAG, "Successfully auto-connected to ADB daemon at $host:$port")
                    _state.value = AutoReconnectState.Connected(host, port)
                    // Stop mDNS discovery once successfully connected
                    mdnsDiscoveryManager.stopDiscovery()

                    autoExecuteOnConnect?.invoke(sessionManager, host, port)
                } else {
                    val error = result.exceptionOrNull()
                    val msg = "Auto-reconnect failed: ${error?.message ?: "Unknown error"}"
                    Log.w(TAG, msg, error)
                    _state.value = AutoReconnectState.Error(msg, error)
                }
            }
        }
    }

    /**
     * Cancels active reconnect listeners and discovery.
     */
    @Synchronized
    fun stopAutoReconnect(resetToIdle: Boolean = true) {
        isRunning = false
        observationJob?.cancel()
        observationJob = null
        mdnsDiscoveryManager.stopDiscovery()
        if (resetToIdle) {
            _state.value = AutoReconnectState.Idle
        }
    }

    override fun close() {
        stopAutoReconnect()
        sessionManager.close()
    }
}
