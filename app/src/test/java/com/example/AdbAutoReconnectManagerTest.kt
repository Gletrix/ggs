package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.adb.AdbAutoReconnectManager
import com.example.adb.AdbKeyStorageManager
import com.example.adb.AdbMdnsDiscoveryManager
import com.example.adb.AdbSessionManager
import com.example.adb.AutoReconnectState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdbAutoReconnectManagerTest {

    private lateinit var context: Context
    private lateinit var keyStorageManager: AdbKeyStorageManager
    private lateinit var sessionManager: AdbSessionManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        keyStorageManager = AdbKeyStorageManager(context)
        sessionManager = AdbSessionManager(keyStorageManager)
    }

    @Test
    fun testInitialStateIsIdle() = runTest {
        val mdnsDiscoveryManager = AdbMdnsDiscoveryManager(context, backgroundScope)
        val autoReconnectManager = AdbAutoReconnectManager(
            context = context,
            keyStorageManager = keyStorageManager,
            mdnsDiscoveryManager = mdnsDiscoveryManager,
            sessionManager = sessionManager,
            scope = backgroundScope
        )

        assertEquals(AutoReconnectState.Idle, autoReconnectManager.state.value)
    }

    @Test
    fun testStartAutoReconnectWithoutKeys_TransitionsToPairingRequired() = runTest {
        val mdnsDiscoveryManager = AdbMdnsDiscoveryManager(context, backgroundScope)
        val autoReconnectManager = AdbAutoReconnectManager(
            context = context,
            keyStorageManager = keyStorageManager,
            mdnsDiscoveryManager = mdnsDiscoveryManager,
            sessionManager = sessionManager,
            scope = backgroundScope
        )

        // Clear any keys
        keyStorageManager.clearKeys()
        assertFalse(keyStorageManager.hasKeys())

        autoReconnectManager.runAutoReconnectPipeline()

        assertEquals(AutoReconnectState.PairingRequired, autoReconnectManager.state.value)
    }

    @Test
    fun testStartAutoReconnectWithKeys_StartsDiscovery() = runTest {
        val mdnsDiscoveryManager = AdbMdnsDiscoveryManager(context, backgroundScope)
        val autoReconnectManager = AdbAutoReconnectManager(
            context = context,
            keyStorageManager = keyStorageManager,
            mdnsDiscoveryManager = mdnsDiscoveryManager,
            sessionManager = sessionManager,
            scope = backgroundScope
        )

        // Generate keys
        keyStorageManager.getAdbKeyBundle()
        assertTrue(keyStorageManager.hasKeys())

        val job = backgroundScope.launch {
            autoReconnectManager.runAutoReconnectPipeline()
        }
        runCurrent()

        assertEquals(AutoReconnectState.DiscoveringConnectService, autoReconnectManager.state.value)
        job.cancel()
    }

    @Test
    fun testStopAutoReconnect_ResetsToIdle() = runTest {
        val mdnsDiscoveryManager = AdbMdnsDiscoveryManager(context, backgroundScope)
        val autoReconnectManager = AdbAutoReconnectManager(
            context = context,
            keyStorageManager = keyStorageManager,
            mdnsDiscoveryManager = mdnsDiscoveryManager,
            sessionManager = sessionManager,
            scope = backgroundScope
        )

        keyStorageManager.getAdbKeyBundle()
        val job = backgroundScope.launch {
            autoReconnectManager.runAutoReconnectPipeline()
        }
        runCurrent()

        autoReconnectManager.stopAutoReconnect(resetToIdle = true)
        job.cancel()
        assertEquals(AutoReconnectState.Idle, autoReconnectManager.state.value)
    }
}
