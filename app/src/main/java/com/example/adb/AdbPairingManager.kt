package com.example.adb

import android.os.Build
import android.util.Log
import io.github.muntashirakon.adb.PairingConnectionCtx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.Security
import javax.net.ssl.SSLException

/**
 * State representing the active Wireless ADB pairing lifecycle.
 */
sealed interface PairingState {
    data object Idle : PairingState
    data class Pairing(val host: String, val port: Int) : PairingState
    data class Success(val host: String, val port: Int) : PairingState
    data class Error(val message: String, val cause: Throwable? = null) : PairingState
}

/**
 * Coordinates Wireless Debugging pairing handshake via libadb-android and SPAKE2.
 */
class AdbPairingManager(
    private val keyStorageManager: AdbKeyStorageManager
) {
    companion object {
        private const val TAG = "AdbPairingManager"
        private const val DEFAULT_CLIENT_NAME = "ADB Screen Resizer"

        init {
            ensureBouncyCastleProvider()
            ensureConscryptProvider()
        }

        fun ensureBouncyCastleProvider() {
            try {
                // Remove Android's restricted system provider and register the full BC library provider
                Security.removeProvider("BC")
                Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
            } catch (_: Exception) {}
        }

        fun ensureConscryptProvider() {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("")
                }
                Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to bypass hidden API or insert Conscrypt", e)
            }
        }
    }

    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    /**
     * Executes the SPAKE2 TLS pairing handshake with adbd at [host]:[port] using the given [pairingCode].
     *
     * @param host IP address or hostname of the ADB daemon pairing service
     * @param port TCP port of the pairing service
     * @param pairingCode 6-digit numeric pairing code
     * @param clientName Human-readable client name shown in Wireless debugging paired devices list
     */
    suspend fun pair(
        host: String,
        port: Int,
        pairingCode: String,
        clientName: String = getDeviceClientName()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        ensureBouncyCastleProvider()
        val trimmedCode = pairingCode.trim()
        if (trimmedCode.length != 6 || !trimmedCode.all { it.isDigit() }) {
            val errorMsg = "Invalid pairing code: must be exactly 6 numeric digits"
            _pairingState.value = PairingState.Error(errorMsg)
            return@withContext Result.failure(IllegalArgumentException(errorMsg))
        }

        if (host.isBlank() || port <= 0 || port > 65535) {
            val errorMsg = "Invalid host/port: $host:$port"
            _pairingState.value = PairingState.Error(errorMsg)
            return@withContext Result.failure(IllegalArgumentException(errorMsg))
        }

        _pairingState.value = PairingState.Pairing(host, port)
        Log.i(TAG, "Starting pairing handshake with $host:$port...")

        var pairingCtx: PairingConnectionCtx? = null
        try {
            val keyBundle = keyStorageManager.getAdbKeyBundle()
            val passwordBytes = trimmedCode.toByteArray(StandardCharsets.UTF_8)

            pairingCtx = PairingConnectionCtx(
                host,
                port,
                passwordBytes,
                keyBundle.privateKey,
                keyBundle.certificate,
                clientName
            )

            // Start blocking TLS SPAKE2 handshake
            pairingCtx.start()

            Log.i(TAG, "Pairing successful with $host:$port (client: $clientName)")
            _pairingState.value = PairingState.Success(host, port)
            Result.success(Unit)
        } catch (e: ConnectException) {
            val msg = "Connection refused at $host:$port. Ensure 'Pair device with pairing code' dialog is open on device."
            Log.e(TAG, msg, e)
            _pairingState.value = PairingState.Error(msg, e)
            Result.failure(IOException(msg, e))
        } catch (e: SocketTimeoutException) {
            val msg = "Pairing timed out connecting to $host:$port."
            Log.e(TAG, msg, e)
            _pairingState.value = PairingState.Error(msg, e)
            Result.failure(IOException(msg, e))
        } catch (e: SSLException) {
            val msg = "Pairing failed: incorrect 6-digit code or TLS handshake error (${e.localizedMessage})."
            Log.e(TAG, msg, e)
            _pairingState.value = PairingState.Error(msg, e)
            Result.failure(e)
        } catch (e: Exception) {
            val msg = "Pairing failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
            Log.e(TAG, msg, e)
            _pairingState.value = PairingState.Error(msg, e)
            Result.failure(e)
        } finally {
            try {
                pairingCtx?.close()
            } catch (closeEx: Exception) {
                Log.w(TAG, "Error closing PairingConnectionCtx", closeEx)
            }
        }
    }

    /**
     * Helper to pair with a discovered ADB service from AdbMdnsDiscoveryManager
     */
    suspend fun pairWithDiscoveredService(
        service: DiscoveredAdbService,
        pairingCode: String,
        clientName: String = getDeviceClientName()
    ): Result<Unit> {
        val host = service.hostAddress ?: service.host?.hostAddress ?: "127.0.0.1"
        return pair(host, service.port, pairingCode, clientName)
    }

    /**
     * Resets the pairing state to Idle
     */
    fun resetState() {
        _pairingState.value = PairingState.Idle
    }

    private fun getDeviceClientName(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL} ($DEFAULT_CLIENT_NAME)"
    }
}
