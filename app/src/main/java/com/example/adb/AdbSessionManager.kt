package com.example.adb

import android.util.Log
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * State representing ADB session connection and command execution lifecycle.
 */
sealed interface AdbSessionState {
    data object Disconnected : AdbSessionState
    data class Connecting(val host: String, val port: Int) : AdbSessionState
    data class Connected(val host: String, val port: Int) : AdbSessionState
    data class ExecutingCommand(val host: String, val port: Int, val command: String) : AdbSessionState
    data class CommandSuccess(
        val host: String,
        val port: Int,
        val command: String,
        val output: String,
        val verificationOutput: String? = null
    ) : AdbSessionState
    data class Error(val message: String, val cause: Throwable? = null) : AdbSessionState
}

/**
 * Result data holder for screen resizing and verification.
 */
data class WmSizeResult(
    val executedCommand: String,
    val applyOutput: String,
    val verificationOutput: String,
    val isVerified: Boolean
)

/**
 * Manages authenticated TLS ADB connection to the adbd daemon connect port (_adb-tls-connect._tcp)
 * and executes shell commands such as `wm size`.
 */
class AdbSessionManager(
    private val keyStorageManager: AdbKeyStorageManager
) : Closeable {

    companion object {
        private const val TAG = "AdbSessionManager"
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 10000L
        const val DEFAULT_SCREEN_WIDTH = 720
        const val DEFAULT_SCREEN_HEIGHT = 1280
    }

    private val _sessionState = MutableStateFlow<AdbSessionState>(AdbSessionState.Disconnected)
    val sessionState: StateFlow<AdbSessionState> = _sessionState.asStateFlow()

    private var activeConnection: AdbConnection? = null
    private var connectedHost: String? = null
    private var connectedPort: Int? = null
    private val connectionLock = Any()

    /**
     * Connects to the adbd TLS connection service at [host]:[port] using the persisted RSA key.
     */
    suspend fun connect(
        host: String,
        port: Int,
        timeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS
    ): Result<AdbConnection> = withContext(Dispatchers.IO) {
        if (host.isBlank() || port <= 0 || port > 65535) {
            val errorMsg = "Invalid connect target: $host:$port"
            _sessionState.value = AdbSessionState.Error(errorMsg)
            return@withContext Result.failure(IllegalArgumentException(errorMsg))
        }

        synchronized(connectionLock) {
            activeConnection?.let { existing ->
                if (existing.isConnected && connectedHost == host && connectedPort == port) {
                    Log.d(TAG, "Reusing existing connected session to $host:$port")
                    return@withContext Result.success(existing)
                }
            }
            closeConnectionInternal()
        }

        _sessionState.value = AdbSessionState.Connecting(host, port)
        Log.i(TAG, "Connecting to ADB TLS service at $host:$port...")

        try {
            val keyBundle = keyStorageManager.getAdbKeyBundle()
            val connection = AdbConnection.create(
                host,
                port,
                keyBundle.privateKey,
                keyBundle.certificate
            )

            // Connect with timeout and throw on unauthorized/pairing required
            val connected = connection.connect(
                timeoutMs,
                TimeUnit.MILLISECONDS,
                true // throw on unauthenticated
            )

            if (!connected && !connection.isConnected) {
                connection.close()
                val msg = "Failed to establish ADB connection to $host:$port"
                _sessionState.value = AdbSessionState.Error(msg)
                return@withContext Result.failure(IOException(msg))
            }

            synchronized(connectionLock) {
                activeConnection = connection
                connectedHost = host
                connectedPort = port
            }

            Log.i(TAG, "Successfully connected and authenticated with ADB daemon at $host:$port")
            _sessionState.value = AdbSessionState.Connected(host, port)
            Result.success(connection)
        } catch (e: AdbPairingRequiredException) {
            val msg = "Device requires pairing first. Please complete 6-digit Wireless Pairing."
            Log.e(TAG, msg, e)
            _sessionState.value = AdbSessionState.Error(msg, e)
            Result.failure(e)
        } catch (e: AdbAuthenticationFailedException) {
            val msg = "ADB Authentication failed. Key rejected by device daemon."
            Log.e(TAG, msg, e)
            _sessionState.value = AdbSessionState.Error(msg, e)
            Result.failure(e)
        } catch (e: ConnectException) {
            val msg = "Connection refused at $host:$port. Ensure Wireless Debugging is enabled in Developer Options."
            Log.e(TAG, msg, e)
            _sessionState.value = AdbSessionState.Error(msg, e)
            Result.failure(IOException(msg, e))
        } catch (e: SocketTimeoutException) {
            val msg = "Connection timed out connecting to $host:$port."
            Log.e(TAG, msg, e)
            _sessionState.value = AdbSessionState.Error(msg, e)
            Result.failure(IOException(msg, e))
        } catch (e: SSLException) {
            val msg = "TLS handshake failed with $host:$port (${e.localizedMessage})."
            Log.e(TAG, msg, e)
            _sessionState.value = AdbSessionState.Error(msg, e)
            Result.failure(e)
        } catch (e: Exception) {
            val msg = "ADB connection failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
            Log.e(TAG, msg, e)
            _sessionState.value = AdbSessionState.Error(msg, e)
            Result.failure(e)
        }
    }

    /**
     * Executes a shell command on the active or supplied connection, returning stdout string.
     */
    suspend fun executeShellCommand(
        command: String,
        host: String? = null,
        port: Int? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val targetHost = host ?: connectedHost
        val targetPort = port ?: connectedPort

        if (targetHost == null || targetPort == null) {
            val msg = "Cannot execute shell command: no active or specified host/port"
            _sessionState.value = AdbSessionState.Error(msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val conn = synchronized(connectionLock) {
            if (activeConnection?.isConnected == true && connectedHost == targetHost && connectedPort == targetPort) {
                activeConnection
            } else {
                null
            }
        } ?: run {
            val connResult = connect(targetHost, targetPort)
            if (connResult.isFailure) {
                return@withContext Result.failure(connResult.exceptionOrNull()!!)
            }
            connResult.getOrThrow()
        }

        _sessionState.value = AdbSessionState.ExecutingCommand(targetHost, targetPort, command)
        Log.i(TAG, "Executing ADB shell command '$command' on $targetHost:$targetPort...")

        var stream: AdbStream? = null
        try {
            // Open shell stream with raw command (format: "shell:<command>")
            val destination = "shell:$command"
            stream = conn.open(destination)

            val output = readStreamOutput(stream)
            Log.i(TAG, "Command '$command' completed. Output: '$output'")

            _sessionState.value = AdbSessionState.CommandSuccess(
                host = targetHost,
                port = targetPort,
                command = command,
                output = output
            )
            Result.success(output)
        } catch (e: Exception) {
            val msg = "Failed executing command '$command': ${e.localizedMessage ?: e.javaClass.simpleName}"
            Log.e(TAG, msg, e)
            _sessionState.value = AdbSessionState.Error(msg, e)
            Result.failure(e)
        } finally {
            try {
                stream?.close()
            } catch (ex: Exception) {
                Log.w(TAG, "Error closing AdbStream", ex)
            }
        }
    }

    /**
     * Executes `wm size <width>x<height>` followed by a verification `wm size` query over the ADB session.
     */
    suspend fun setScreenSizeAndVerify(
        host: String,
        port: Int,
        width: Int = DEFAULT_SCREEN_WIDTH,
        height: Int = DEFAULT_SCREEN_HEIGHT
    ): Result<WmSizeResult> = withContext(Dispatchers.IO) {
        val applyCommand = "wm size ${width}x${height}"
        val queryCommand = "wm size"

        val applyResult = executeShellCommand(applyCommand, host, port)
        if (applyResult.isFailure) {
            return@withContext Result.failure(applyResult.exceptionOrNull()!!)
        }
        val applyOutput = applyResult.getOrDefault("")

        // Verification query
        val verifyResult = executeShellCommand(queryCommand, host, port)
        if (verifyResult.isFailure) {
            return@withContext Result.failure(verifyResult.exceptionOrNull()!!)
        }
        val verificationOutput = verifyResult.getOrDefault("")

        val expectedDimensionPattern = "${width}x${height}"
        val isVerified = verificationOutput.contains(expectedDimensionPattern)

        Log.i(
            TAG,
            "Screen size set to ${width}x${height}. Verification stdout: '$verificationOutput' (isVerified=$isVerified)"
        )

        _sessionState.value = AdbSessionState.CommandSuccess(
            host = host,
            port = port,
            command = applyCommand,
            output = applyOutput,
            verificationOutput = verificationOutput
        )

        Result.success(
            WmSizeResult(
                executedCommand = applyCommand,
                applyOutput = applyOutput,
                verificationOutput = verificationOutput,
                isVerified = isVerified
            )
        )
    }

    /**
     * Convenience helper to run setScreenSizeAndVerify on a discovered connect service.
     */
    suspend fun setScreenSizeOnDiscoveredService(
        service: DiscoveredAdbService,
        width: Int = DEFAULT_SCREEN_WIDTH,
        height: Int = DEFAULT_SCREEN_HEIGHT
    ): Result<WmSizeResult> {
        val host = service.hostAddress ?: service.host?.hostAddress ?: "127.0.0.1"
        return setScreenSizeAndVerify(host, service.port, width, height)
    }

    /**
     * Reads all bytes from an AdbStream until closed or EOF.
     */
    private fun readStreamOutput(stream: AdbStream): String {
        val inputStream = stream.openInputStream()
        val buffer = ByteArray(1024)
        val outputStream = ByteArrayOutputStream()
        var readBytes: Int

        try {
            while (true) {
                readBytes = inputStream.read(buffer)
                if (readBytes == -1) break
                outputStream.write(buffer, 0, readBytes)
                // If stream is closed by remote, stop reading
                if (stream.isClosed && inputStream.available() <= 0) {
                    break
                }
            }
        } catch (e: IOException) {
            // Stream EOF / broken pipe on remote exit
            Log.d(TAG, "Stream reading terminated: ${e.message}")
        }

        return outputStream.toString(StandardCharsets.UTF_8.name()).trim()
    }

    private fun closeConnectionInternal() {
        try {
            activeConnection?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing active AdbConnection", e)
        } finally {
            activeConnection = null
            connectedHost = null
            connectedPort = null
        }
    }

    override fun close() {
        synchronized(connectionLock) {
            closeConnectionInternal()
            _sessionState.value = AdbSessionState.Disconnected
        }
    }
}
