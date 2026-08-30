package com.example.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.util.ArrayDeque

/**
 * Service type identifiers for Android Wireless Debugging via mDNS
 */
enum class AdbServiceType(val serviceTypeString: String) {
    PAIRING("_adb-tls-pairing._tcp"),
    CONNECT("_adb-tls-connect._tcp");

    companion object {
        fun fromServiceType(type: String): AdbServiceType? {
            val normalized = type.trimEnd('.')
            return entries.firstOrNull {
                normalized.equals(it.serviceTypeString, ignoreCase = true) ||
                        normalized.startsWith(it.serviceTypeString, ignoreCase = true)
            }
        }
    }
}

/**
 * Discovered ADB Service instance metadata
 */
data class DiscoveredAdbService(
    val serviceType: AdbServiceType,
    val serviceName: String,
    val host: InetAddress?,
    val hostAddress: String?,
    val port: Int,
    val attributes: Map<String, ByteArray> = emptyMap()
)

/**
 * State representing active discovery sessions and detected services
 */
data class AdbDiscoveryState(
    val isSearchingPairing: Boolean = false,
    val isSearchingConnect: Boolean = false,
    val pairingServices: List<DiscoveredAdbService> = emptyList(),
    val connectServices: List<DiscoveredAdbService> = emptyList(),
    val errorMessage: String? = null
) {
    val isSearching: Boolean
        get() = isSearchingPairing || isSearchingConnect
}

/**
 * Manages mDNS network service discovery for ADB pairing and connection services
 * using Android's NsdManager and acquires WifiManager.MulticastLock.
 */
class AdbMdnsDiscoveryManager(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        private const val TAG = "AdbMdnsDiscovery"
        private const val MULTICAST_LOCK_TAG = "AdbMdnsDiscoveryMulticastLock"
    }

    private val appContext = context.applicationContext
    private val nsdManager: NsdManager? =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager: WifiManager? =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null

    private val _discoveryState = MutableStateFlow(AdbDiscoveryState())
    val discoveryState: StateFlow<AdbDiscoveryState> = _discoveryState.asStateFlow()

    private val _serviceEvents = MutableSharedFlow<DiscoveredAdbService>(extraBufferCapacity = 64)
    val serviceEvents: SharedFlow<DiscoveredAdbService> = _serviceEvents.asSharedFlow()

    private var pairingDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var connectDiscoveryListener: NsdManager.DiscoveryListener? = null

    private val resolveQueue = ArrayDeque<Pair<NsdServiceInfo, AdbServiceType>>()
    private var isResolving = false
    private val lock = Any()

    @Synchronized
    fun startDiscovery(
        discoverPairing: Boolean = true,
        discoverConnect: Boolean = true
    ) {
        if (nsdManager == null) {
            _discoveryState.update { it.copy(errorMessage = "NsdManager is not available on this device") }
            Log.e(TAG, "NsdManager service not found")
            return
        }

        acquireMulticastLock()

        if (discoverPairing && pairingDiscoveryListener == null) {
            val listener = createDiscoveryListener(AdbServiceType.PAIRING)
            pairingDiscoveryListener = listener
            try {
                nsdManager.discoverServices(
                    AdbServiceType.PAIRING.serviceTypeString,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start pairing service discovery", e)
                pairingDiscoveryListener = null
                _discoveryState.update { it.copy(errorMessage = "Failed to start pairing discovery: ${e.message}") }
            }
        }

        if (discoverConnect && connectDiscoveryListener == null) {
            val listener = createDiscoveryListener(AdbServiceType.CONNECT)
            connectDiscoveryListener = listener
            try {
                nsdManager.discoverServices(
                    AdbServiceType.CONNECT.serviceTypeString,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start connect service discovery", e)
                connectDiscoveryListener = null
                _discoveryState.update { it.copy(errorMessage = "Failed to start connect discovery: ${e.message}") }
            }
        }
    }

    @Synchronized
    fun stopDiscovery() {
        if (nsdManager == null) return

        pairingDiscoveryListener?.let { listener ->
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping pairing discovery", e)
            }
            pairingDiscoveryListener = null
        }

        connectDiscoveryListener?.let { listener ->
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping connect discovery", e)
            }
            connectDiscoveryListener = null
        }

        synchronized(lock) {
            resolveQueue.clear()
            isResolving = false
        }

        releaseMulticastLock()

        _discoveryState.update {
            it.copy(
                isSearchingPairing = false,
                isSearchingConnect = false
            )
        }
    }

    @Synchronized
    fun clearDiscoveredServices() {
        _discoveryState.update {
            it.copy(
                pairingServices = emptyList(),
                connectServices = emptyList(),
                errorMessage = null
            )
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock == null) {
            multicastLock = wifiManager?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
                setReferenceCounted(false)
            }
        }
        try {
            multicastLock?.let {
                if (!it.isHeld) {
                    it.acquire()
                    Log.d(TAG, "WifiManager.MulticastLock acquired")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WifiManager.MulticastLock", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WifiManager.MulticastLock released")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WifiManager.MulticastLock", e)
        }
    }

    private fun createDiscoveryListener(expectedType: AdbServiceType): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Discovery started for $regType")
                _discoveryState.update {
                    when (expectedType) {
                        AdbServiceType.PAIRING -> it.copy(isSearchingPairing = true, errorMessage = null)
                        AdbServiceType.CONNECT -> it.copy(isSearchingConnect = true, errorMessage = null)
                    }
                }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: name=${serviceInfo.serviceName}, type=${serviceInfo.serviceType}")
                val resolvedType = AdbServiceType.fromServiceType(serviceInfo.serviceType) ?: expectedType
                enqueueResolve(serviceInfo, resolvedType)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: name=${serviceInfo.serviceName}")
                val resolvedType = AdbServiceType.fromServiceType(serviceInfo.serviceType) ?: expectedType
                handleServiceLost(serviceInfo.serviceName, resolvedType)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped for $serviceType")
                _discoveryState.update {
                    when (expectedType) {
                        AdbServiceType.PAIRING -> it.copy(isSearchingPairing = false)
                        AdbServiceType.CONNECT -> it.copy(isSearchingConnect = false)
                    }
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed for $serviceType with code $errorCode")
                _discoveryState.update {
                    when (expectedType) {
                        AdbServiceType.PAIRING -> it.copy(
                            isSearchingPairing = false,
                            errorMessage = "Pairing discovery start failed ($errorCode)"
                        )
                        AdbServiceType.CONNECT -> it.copy(
                            isSearchingConnect = false,
                            errorMessage = "Connect discovery start failed ($errorCode)"
                        )
                    }
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed for $serviceType with code $errorCode")
            }
        }
    }

    private fun enqueueResolve(serviceInfo: NsdServiceInfo, serviceType: AdbServiceType) {
        synchronized(lock) {
            resolveQueue.add(Pair(serviceInfo, serviceType))
            if (!isResolving) {
                resolveNextInQueue()
            }
        }
    }

    private fun resolveNextInQueue() {
        synchronized(lock) {
            if (resolveQueue.isEmpty()) {
                isResolving = false
                return
            }
            isResolving = true
            val (nextService, serviceType) = resolveQueue.poll() ?: run {
                isResolving = false
                return
            }

            if (nsdManager == null) {
                isResolving = false
                return
            }

            try {
                nsdManager.resolveService(nextService, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: errorCode=$errorCode")
                        synchronized(lock) {
                            resolveNextInQueue()
                        }
                    }

                    override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                        Log.d(
                            TAG,
                            "Service resolved: ${resolvedInfo.serviceName} at ${resolvedInfo.host}:${resolvedInfo.port}"
                        )
                        handleServiceResolved(resolvedInfo, serviceType)
                        synchronized(lock) {
                            resolveNextInQueue()
                        }
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Exception calling resolveService for ${nextService.serviceName}", e)
                resolveNextInQueue()
            }
        }
    }

    private fun handleServiceResolved(resolvedInfo: NsdServiceInfo, serviceType: AdbServiceType) {
        val host = resolvedInfo.host
        val hostAddress = host?.hostAddress
        val port = resolvedInfo.port
        val name = resolvedInfo.serviceName

        val service = DiscoveredAdbService(
            serviceType = serviceType,
            serviceName = name,
            host = host,
            hostAddress = hostAddress,
            port = port,
            attributes = resolvedInfo.attributes ?: emptyMap()
        )

        _discoveryState.update { current ->
            when (serviceType) {
                AdbServiceType.PAIRING -> {
                    val updatedList = current.pairingServices
                        .filterNot { it.serviceName == name } + service
                    current.copy(pairingServices = updatedList)
                }
                AdbServiceType.CONNECT -> {
                    val updatedList = current.connectServices
                        .filterNot { it.serviceName == name } + service
                    current.copy(connectServices = updatedList)
                }
            }
        }

        scope.launch {
            _serviceEvents.emit(service)
        }
    }

    private fun handleServiceLost(serviceName: String, serviceType: AdbServiceType) {
        _discoveryState.update { current ->
            when (serviceType) {
                AdbServiceType.PAIRING -> {
                    current.copy(pairingServices = current.pairingServices.filterNot { it.serviceName == serviceName })
                }
                AdbServiceType.CONNECT -> {
                    current.copy(connectServices = current.connectServices.filterNot { it.serviceName == serviceName })
                }
            }
        }
    }
}
