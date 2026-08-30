package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.adb.AdbKeyStorageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdbKeyStorageManagerTest {

    private lateinit var keyManager: AdbKeyStorageManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        keyManager = AdbKeyStorageManager(context, "test_adb_keys_prefs")
        keyManager.clearKeys()
    }

    @Test
    fun testGenerateAndPersistKeys() {
        assertFalse(keyManager.hasKeys())

        val bundle1 = keyManager.getAdbKeyBundle()
        assertNotNull(bundle1.privateKey)
        assertNotNull(bundle1.publicKey)
        assertNotNull(bundle1.certificate)
        assertTrue(keyManager.hasKeys())

        val rsaPub = bundle1.publicKey as RSAPublicKey
        val rsaPriv = bundle1.privateKey as RSAPrivateKey
        assertEquals(rsaPub.modulus, rsaPriv.modulus)

        // Verify cryptographic signing and verification integrity
        val testData = "Test ADB authentication payload 12345".toByteArray()
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(bundle1.privateKey)
        signer.update(testData)
        val signature = signer.sign()

        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(bundle1.publicKey)
        verifier.update(testData)
        assertTrue(verifier.verify(signature))

        // Reload from storage with a new manager instance
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val reloadedManager = AdbKeyStorageManager(context, "test_adb_keys_prefs")
        assertTrue(reloadedManager.hasKeys())

        val bundle2 = reloadedManager.getAdbKeyBundle()
        assertEquals(bundle1.publicKey, bundle2.publicKey)
        assertEquals(bundle1.privateKey, bundle2.privateKey)
        assertEquals(bundle1.certificate, bundle2.certificate)
    }

    @Test
    fun testClearKeys() {
        keyManager.getAdbKeyBundle()
        assertTrue(keyManager.hasKeys())

        keyManager.clearKeys()
        assertFalse(keyManager.hasKeys())
    }
}
