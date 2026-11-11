package com.pickle.patcher.lib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkSignerToolTest {

    @JvmField
    @Rule
    val tmp = TemporaryFolder()

    private fun keystore(): SigningKeystore =
        SigningKeystore.loadBytes(
            repoFile("android", "debug", "patcher-release.p12").readBytes(),
            "android".toCharArray(),
        )

    @Test
    fun `sign then verify roundtrip`() {
        val src = tmp.newFile("in.apk")
        src.writeBytes(fakeApk())
        val out = tmp.newFile("out.apk")

        val signed = ApkSignerTool.sign(src, out, keystore(), minSdk = 24)
        val raw = com.android.apksig.ApkVerifier.Builder(out).build().verify()
        println(
            "DEBUG verified=${signed.verified} rawVerified=${raw.isVerified} " +
                "errors=${raw.errors} allErrors=${raw.allErrors} warnings=${raw.warnings} " +
                "signers=${raw.signerCertificates.size} v1signers=${raw.v1SchemeSigners.size} v2signers=${raw.v2SchemeSigners.size} " +
                "v1=${raw.isVerifiedUsingV1Scheme} v2=${raw.isVerifiedUsingV2Scheme}",
        )
        assertTrue("errors: ${signed.errors}", signed.verified)
        assertTrue(signed.usedV1 || signed.usedV2)

        val verification = ApkSignerTool.verify(out)
        assertTrue(verification.verified)
        assertTrue(verification.signerFingerprintSha256.isNotBlank())
        assertTrue(verification.usedV1 || verification.usedV2)
    }

    @Test
    fun `verification reports same signer across files`() {
        val src = tmp.newFile("in.apk")
        src.writeBytes(fakeApk())
        val a = tmp.newFile("a.apk")
        val b = tmp.newFile("b.apk")

        ApkSignerTool.sign(src, a, keystore(), minSdk = 24)
        ApkSignerTool.sign(src, b, keystore(), minSdk = 24)

        assertEquals(
            ApkSignerTool.verify(a).signerFingerprintSha256,
            ApkSignerTool.verify(b).signerFingerprintSha256,
        )
    }
}
