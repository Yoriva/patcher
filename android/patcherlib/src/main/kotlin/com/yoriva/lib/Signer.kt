package com.yoriva.lib
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
class SigningKeystore(
    private val privateKeyRef: PrivateKey,
    private val certificateChainRef: List<X509Certificate>,
) {
    val privateKey: PrivateKey get() = privateKeyRef
    val certificateChain: List<X509Certificate> get() = certificateChainRef
    val certificate: X509Certificate get() = certificateChain.first()
    fun fingerprintSha256(): String = fingerprint(certificate, "SHA-256")
    companion object {
        fun fingerprint(cert: X509Certificate, algo: String): String {
            val md = MessageDigest.getInstance(algo)
            return md.digest(cert.encoded).joinToString("") { "%02X".format(it) }
        }
        const val DEFAULT_STORE_PASSWORD = "android"
        const val DEFAULT_KEY_PASSWORD = "android"
        const val DEFAULT_ALIAS = "androiddebugkey"
        fun load(file: File, storePassword: CharArray = DEFAULT_STORE_PASSWORD.toCharArray()): SigningKeystore {
            return loadBytes(file.readBytes(), storePassword)
        }
        fun loadBytes(bytes: ByteArray, storePassword: CharArray = DEFAULT_STORE_PASSWORD.toCharArray()): SigningKeystore {
            if (bytes.isEmpty()) throw IllegalStateException("Keystore is empty")
            var last: Exception? = null
            for (format in listOf("PKCS12", KeyStore.getDefaultType())) {
                try {
                    val ks = KeyStore.getInstance(format)
                    ks.load(bytes.inputStream(), storePassword)
                    if (ks.containsAlias(DEFAULT_ALIAS)) {
                        return fromKeyStore(ks)
                    }
                    last = IllegalStateException("Keystore loaded but no alias '${DEFAULT_ALIAS}' found")
                } catch (e: Exception) {
                    last = e
                }
            }
            throw IllegalStateException("Unable to load keystore", last)
        }
        fun fromKeyStore(ks: KeyStore): SigningKeystore {
            val key = (ks.getKey(DEFAULT_ALIAS, DEFAULT_KEY_PASSWORD.toCharArray()) as? PrivateKey)
                ?: throw IllegalStateException("No private key for alias '$DEFAULT_ALIAS'")
            val chain = ks.getCertificateChain(DEFAULT_ALIAS)
                ?.filterIsInstance<X509Certificate>()
                ?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("No certificate chain for alias '$DEFAULT_ALIAS'")
            return SigningKeystore(key, chain)
        }
        fun loadPem(
            privateKeyPem: String,
            certificatePem: String,
        ): SigningKeystore {
            val keyBytes = decodePem(privateKeyPem, "PRIVATE KEY")
            val key = try {
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            } catch (e: Exception) {
                throw IllegalStateException("Unsupported private key: ${e.message}", e)
            }
            val certBytes = decodePem(certificatePem, "CERTIFICATE")
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(certBytes.inputStream()) as X509Certificate
            return SigningKeystore(key, listOf(cert))
        }
        private fun decodePem(pem: String, label: String): ByteArray {
            val body = pem
                .replace(Regex("-----(BEGIN|END) $label-----"), "")
                .replace(Regex("\\s"), "")
            return pemBase64Decode(body)
        }
        private fun pemBase64Decode(s: String): ByteArray {
            fun v(c: Char): Int = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+', '-' -> 62
                '/', '_' -> 63
                else -> -1
            }
            val out = java.io.ByteArrayOutputStream((s.length * 3) / 4)
            var bits = 0
            var nBits = 0
            for (c in s) {
                if (c == '=') break
                val d = v(c)
                if (d < 0) continue
                bits = (bits shl 6) or d
                nBits += 6
                if (nBits >= 8) {
                    nBits -= 8
                    out.write((bits shr nBits) and 0xFF)
                }
            }
            return out.toByteArray()
        }
    }
}
object ApkSignerTool {
    data class SignOutcome(
        val unsigned: File,
        val signed: File,
        val verified: Boolean,
        val signerFingerprintSha256: String,
        val usedV1: Boolean,
        val usedV2: Boolean,
        val errors: List<String>,
    )
    fun sign(
        unsignedApk: File,
        outputApk: File,
        keystore: SigningKeystore,
        minSdk: Int = 24,
    ): SignOutcome {
        val config = ApkSigner.SignerConfig.Builder(
            "amxx-patcher",
            keystore.privateKey,
            keystore.certificateChain,
        ).build()
        val signer = ApkSigner.Builder(listOf(config))
            .setInputApk(unsignedApk)
            .setOutputApk(outputApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(false)
            .setV4SigningEnabled(false)
            .setMinSdkVersion(minSdk)
            .setAlignmentPreserved(true)   
            .setDebuggableApkPermitted(true)
            .build()
        signer.sign()
        val verification = verify(outputApk)
        return SignOutcome(
            unsigned = unsignedApk,
            signed = outputApk,
            verified = verification.verified,
            signerFingerprintSha256 = verification.signerFingerprintSha256,
            usedV1 = verification.usedV1,
            usedV2 = verification.usedV2,
            errors = verification.errors,
        )
    }
    data class Verification(
        val verified: Boolean,
        val signerFingerprintSha256: String,
        val signerFingerprintSha1: String,
        val usedV1: Boolean,
        val usedV2: Boolean,
        val errors: List<String>,
    )
    fun verify(apkFile: File): Verification {
        val result = ApkVerifier.Builder(apkFile).build().verify()
        val certs = result.signerCertificates
        val fp256 = if (certs.isNotEmpty()) SigningKeystore.fingerprint(certs.first(), "SHA-256") else ""
        val fp1 = if (certs.isNotEmpty()) SigningKeystore.fingerprint(certs.first(), "SHA-1") else ""
        val errors = result.errors.map { it.toString() }
        return Verification(
            verified = result.isVerified,
            signerFingerprintSha256 = fp256,
            signerFingerprintSha1 = fp1,
            usedV1 = result.isVerifiedUsingV1Scheme,
            usedV2 = result.isVerifiedUsingV2Scheme,
            errors = errors,
        )
    }
}