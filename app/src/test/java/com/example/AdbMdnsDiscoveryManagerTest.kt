package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.adb.AdbDiscoveryState
import com.example.adb.AdbMdnsDiscoveryManager
import com.example.adb.AdbServiceType
import com.example.adb.DiscoveredAdbService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdbMdnsDiscoveryManagerTest {

    private lateinit var testScope: TestScope
    private lateinit var discoveryManager: AdbMdnsDiscoveryManager

    @Before
    fun setUp() {
        val testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        discoveryManager = AdbMdnsDiscoveryManager(context, testScope)
    }

    @Test
    fun testAdbServiceTypeMatching() {
        assertEquals(AdbServiceType.PAIRING, AdbServiceType.fromServiceType("_adb-tls-pairing._tcp"))
        assertEquals(AdbServiceType.PAIRING, AdbServiceType.fromServiceType("_adb-tls-pairing._tcp."))
        assertEquals(AdbServiceType.CONNECT, AdbServiceType.fromServiceType("_adb-tls-connect._tcp"))
        assertEquals(AdbServiceType.CONNECT, AdbServiceType.fromServiceType("_adb-tls-connect._tcp."))
        assertNull(AdbServiceType.fromServiceType("_http._tcp"))
    }

    @Test
    fun testInitialDiscoveryState() {
        val state = discoveryManager.discoveryState.value
        assertFalse(state.isSearching)
        assertFalse(state.isSearchingPairing)
        assertFalse(state.isSearchingConnect)
        assertTrue(state.pairingServices.isEmpty())
        assertTrue(state.connectServices.isEmpty())
        assertNull(state.errorMessage)
    }

    @Test
    fun testDiscoveredAdbServiceDataStructure() {
        val host = InetAddress.getByName("192.168.1.100")
        val service = DiscoveredAdbService(
            serviceType = AdbServiceType.PAIRING,
            serviceName = "adb-pairing-12345",
            host = host,
            hostAddress = "192.168.1.100",
            port = 37123
        )

        assertEquals(AdbServiceType.PAIRING, service.serviceType)
        assertEquals("adb-pairing-12345", service.serviceName)
        assertEquals("192.168.1.100", service.hostAddress)
        assertEquals(37123, service.port)
    }

    @Test
    fun testStartAndStopDiscoveryLifecycle() {
        // Starts discovery without throwing
        discoveryManager.startDiscovery(discoverPairing = true, discoverConnect = true)
        
        // Stop discovery cleans up cleanly
        discoveryManager.stopDiscovery()
        val state = discoveryManager.discoveryState.value
        assertFalse(state.isSearchingPairing)
        assertFalse(state.isSearchingConnect)
    }
}
