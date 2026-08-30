package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.adb.AdbKeyStorageManager
import com.example.adb.AdbServiceType
import com.example.adb.AdbSessionManager
import com.example.adb.AdbSessionState
import com.example.adb.DiscoveredAdbService
import com.example.adb.WmSizeResult
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
class AdbSessionManagerTest {

    private lateinit var keyStorageManager: AdbKeyStorageManager
    private lateinit var sessionManager: AdbSessionManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        keyStorageManager = AdbKeyStorageManager(context)
        sessionManager = AdbSessionManager(keyStorageManager)
    }

    @Test
    fun testInitialStateIsDisconnected() {
        assertEquals(AdbSessionState.Disconnected, sessionManager.sessionState.value)
    }

    @Test
    fun testInvalidHostAndPortValidation() = runTest {
        // Empty host
        val res1 = sessionManager.connect("", 5555)
        assertTrue(res1.isFailure)
        assertTrue(sessionManager.sessionState.value is AdbSessionState.Error)

        // Invalid port
        val res2 = sessionManager.connect("127.0.0.1", -1)
        assertTrue(res2.isFailure)
        assertTrue(sessionManager.sessionState.value is AdbSessionState.Error)

        val res3 = sessionManager.connect("127.0.0.1", 70000)
        assertTrue(res3.isFailure)
        assertTrue(sessionManager.sessionState.value is AdbSessionState.Error)
    }

    @Test
    fun testExecuteCommandWithoutHostOrActiveConnection() = runTest {
        val res = sessionManager.executeShellCommand("wm size")
        assertTrue(res.isFailure)
        assertTrue(sessionManager.sessionState.value is AdbSessionState.Error)
    }

    @Test
    fun testConnectionRefusedHandling() = runTest {
        // Attempting to connect to an unlistened localhost port
        val res = sessionManager.connect("127.0.0.1", 64991, timeoutMs = 2000L)
        assertTrue(res.isFailure)
        val state = sessionManager.sessionState.value
        assertTrue("Expected AdbSessionState.Error but was $state", state is AdbSessionState.Error)
    }

    @Test
    fun testWmSizeResultModel() {
        val wmResult = WmSizeResult(
            executedCommand = "wm size 720x1600",
            applyOutput = "",
            verificationOutput = "Physical size: 1080x2400\nOverride size: 720x1600",
            isVerified = true
        )

        assertEquals("wm size 720x1600", wmResult.executedCommand)
        assertTrue(wmResult.isVerified)
        assertTrue(wmResult.verificationOutput.contains("720x1600"))
    }

    @Test
    fun testSetScreenSizeOnDiscoveredServiceFailure() = runTest {
        val service = DiscoveredAdbService(
            serviceType = AdbServiceType.CONNECT,
            serviceName = "adb-connect-test",
            host = InetAddress.getByName("127.0.0.1"),
            hostAddress = "127.0.0.1",
            port = 64990
        )

        val res = sessionManager.setScreenSizeOnDiscoveredService(service, 720, 1600)
        assertTrue(res.isFailure)
        assertTrue(sessionManager.sessionState.value is AdbSessionState.Error)
    }

    @Test
    fun testCloseResetsState() {
        sessionManager.close()
        assertEquals(AdbSessionState.Disconnected, sessionManager.sessionState.value)
    }
}
