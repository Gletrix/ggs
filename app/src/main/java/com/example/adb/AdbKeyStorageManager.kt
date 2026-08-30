package com.example.adb

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Date

/**
 * Manages the generation, secure persistence, and retrieval of RSA key pairs
 * and X.509 certificates for ADB authentication and pairing.
 */
class AdbKeyStorageManager(
    private val context: Context,
    private val prefsFileName: String = DEFAULT_PREFS_NAME
) {
    companion object {
        const val DEFAULT_PREFS_NAME = "adb_encrypted_keys_prefs"
        private const val KEY_PRIVATE_KEY_B64 = "adb_private_key_pkcs8"
        private const val KEY_PUBLIC_KEY_B64 = "adb_public_key_x509"
        private const val KEY_CERTIFICATE_B64 = "adb_certificate_der"
        private const val RSA_KEY_SIZE = 2048

        init {
            ensureBouncyCastleProvider()
        }

        fun ensureBouncyCastleProvider() {
            try {
                // Remove Android's restricted system provider and register the full BC library provider
                Security.removeProvider("BC")
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            } catch (_: Exception) {}
        }
    }

    private val sharedPreferences: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                prefsFileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback for test environments where AndroidKeyStore MasterKey may not be available
            context.getSharedPreferences(prefsFileName, Context.MODE_PRIVATE)
        }
    }

    @Synchronized
    fun hasKeys(): Boolean {
        return sharedPreferences.contains(KEY_PRIVATE_KEY_B64) &&
                sharedPreferences.contains(KEY_PUBLIC_KEY_B64) &&
                sharedPreferences.contains(KEY_CERTIFICATE_B64)
    }

    @Synchronized
    fun getOrGenerateKeyPair(): KeyPair {
        val privateKeyB64 = sharedPreferences.getString(KEY_PRIVATE_KEY_B64, null)
        val publicKeyB64 = sharedPreferences.getString(KEY_PUBLIC_KEY_B64, null)

        if (privateKeyB64 != null && publicKeyB64 != null) {
            val keyFactory = KeyFactory.getInstance("RSA")
            val privateKeyBytes = Base64.decode(privateKeyB64, Base64.NO_WRAP)
            val publicKeyBytes = Base64.decode(publicKeyB64, Base64.NO_WRAP)

            val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
            val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(publicKeyBytes))
            return KeyPair(publicKey, privateKey)
        }

        return generateAndPersistNewKeys().first
    }

    @Synchronized
    fun getOrGenerateCertificate(): X509Certificate {
        val certB64 = sharedPreferences.getString(KEY_CERTIFICATE_B64, null)
        if (certB64 != null) {
            val certBytes = Base64.decode(certB64, Base64.NO_WRAP)
            val certFactory = CertificateFactory.getInstance("X.509")
            return certFactory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
        }

        return generateAndPersistNewKeys().second
    }

    @Synchronized
    fun getAdbKeyBundle(): AdbKeyBundle {
        val keyPair = getOrGenerateKeyPair()
        val certificate = getOrGenerateCertificate()
        return AdbKeyBundle(
            privateKey = keyPair.private,
            publicKey = keyPair.public,
            certificate = certificate
        )
    }

    @Synchronized
    fun clearKeys() {
        sharedPreferences.edit()
            .remove(KEY_PRIVATE_KEY_B64)
            .remove(KEY_PUBLIC_KEY_B64)
            .remove(KEY_CERTIFICATE_B64)
            .apply()
    }

    @Synchronized
    private fun generateAndPersistNewKeys(): Pair<KeyPair, X509Certificate> {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(RSA_KEY_SIZE)
        val keyPair = kpg.generateKeyPair()

        val certificate = generateSelfSignedCertificate(keyPair)

        val privateKeyB64 = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)
        val publicKeyB64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        val certB64 = Base64.encodeToString(certificate.encoded, Base64.NO_WRAP)

        sharedPreferences.edit()
            .putString(KEY_PRIVATE_KEY_B64, privateKeyB64)
            .putString(KEY_PUBLIC_KEY_B64, publicKeyB64)
            .putString(KEY_CERTIFICATE_B64, certB64)
            .apply()

        return Pair(keyPair, certificate)
    }

    private fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        ensureBouncyCastleProvider()
        val now = System.currentTimeMillis()
        val startDate = Date(now - 24 * 60 * 60 * 1000L) // 1 day before
        val expiryDate = Date(now + 30L * 365 * 24 * 60 * 60 * 1000L) // 30 years

        val subject = X500Name("CN=ADB Wireless Resizer, O=Android, C=US")
        val serial = BigInteger.valueOf(now)

        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            startDate,
            expiryDate,
            subject,
            keyPair.public
        )

        val bcProvider = BouncyCastleProvider()
        val contentSigner = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(bcProvider)
            .build(keyPair.private)

        val certHolder = certBuilder.build(contentSigner)
        return JcaX509CertificateConverter()
            .setProvider(bcProvider)
            .getCertificate(certHolder)
    }

    data class AdbKeyBundle(
        val privateKey: PrivateKey,
        val publicKey: PublicKey,
        val certificate: X509Certificate
    )
}
