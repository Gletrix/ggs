package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.adb.AdbKeyStorageManager
import com.example.adb.AdbPairingManager
import com.example.adb.AdbServiceType
import com.example.adb.DiscoveredAdbService
import com.example.adb.PairingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class AdbPairingManagerTest {

    private lateinit var keyStorageManager: AdbKeyStorageManager
    private lateinit var pairingManager: AdbPairingManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        keyStorageManager = AdbKeyStorageManager(context)
        pairingManager = AdbPairingManager(keyStorageManager)
    }

    @Test
    fun testInitialStateIsIdle() {
        assertEquals(PairingState.Idle, pairingManager.pairingState.value)
    }

    @Test
    fun testInvalidPairingCodeValidation() = runTest {
        // Less than 6 digits
        val result1 = pairingManager.pair("127.0.0.1", 37000, "12345")
        assertTrue(result1.isFailure)
        assertTrue(pairingManager.pairingState.value is PairingState.Error)

        // Non numeric
        val result2 = pairingManager.pair("127.0.0.1", 37000, "12345a")
        assertTrue(result2.isFailure)
        assertTrue(pairingManager.pairingState.value is PairingState.Error)

        // More than 6 digits
        val result3 = pairingManager.pair("127.0.0.1", 37000, "1234567")
        assertTrue(result3.isFailure)
        assertTrue(pairingManager.pairingState.value is PairingState.Error)
    }

    @Test
    fun testInvalidHostOrPort() = runTest {
        val result1 = pairingManager.pair("", 37000, "123456")
        assertTrue(result1.isFailure)
        assertTrue(pairingManager.pairingState.value is PairingState.Error)

        val result2 = pairingManager.pair("127.0.0.1", -1, "123456")
        assertTrue(result2.isFailure)
        assertTrue(pairingManager.pairingState.value is PairingState.Error)

        val result3 = pairingManager.pair("127.0.0.1", 70000, "123456")
        assertTrue(result3.isFailure)
        assertTrue(pairingManager.pairingState.value is PairingState.Error)
    }

    @Test
    fun testPairingConnectionRefusedHandling() = runTest {
        // Pairing with an unlistened localhost port should catch ConnectException / socket error and return Failure cleanly
        val result = pairingManager.pair("127.0.0.1", 64999, "123456")
        assertTrue(result.isFailure)
        val state = pairingManager.pairingState.value
        assertTrue("Expected PairingState.Error but was $state", state is PairingState.Error)
    }

    @Test
    fun testPairWithDiscoveredServiceHelper() = runTest {
        val service = DiscoveredAdbService(
            serviceType = AdbServiceType.PAIRING,
            serviceName = "adb-test",
            host = InetAddress.getByName("127.0.0.1"),
            hostAddress = "127.0.0.1",
            port = 64998
        )
        val result = pairingManager.pairWithDiscoveredService(service, "654321")
        assertTrue(result.isFailure)
        assertTrue(pairingManager.pairingState.value is PairingState.Error)
    }

    @Test
    fun testResetState() {
        pairingManager.resetState()
        assertEquals(PairingState.Idle, pairingManager.pairingState.value)
    }
}
