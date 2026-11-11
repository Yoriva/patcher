package com.pickle.patcher.lib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkPatcherTest {

    @JvmField
    @Rule
    val tmp = TemporaryFolder()

    private fun keystore(): SigningKeystore =
        SigningKeystore.loadBytes(
            repoFile("android", "debug", "patcher-release.p12").readBytes(),
            "android".toCharArray(),
        )

    @Test
    fun `patch injects bundle and produces a verifiable apk`() {
        val src = tmp.newFile("src.apk")
        src.writeBytes(fakeApk())
        val out = tmp.newFile("out.apk")

        val manifest = BundleManifest(
            version = "test",
            abi = "arm64-v8a",
            entries = listOf(
                BundleManifest.BundleEntry(
                    source = "libamxmodx",
                    target = "lib/arm64-v8a/libamxmodx.so",
                    method = BundleManifest.Compression.DEFLATED,
                    required = true,
                    description = "amxmodx",
                ),
            ),
        )
        val bundle = Bundle(manifest, mapOf("libamxmodx" to ByteArray(128) { 7 }), emptyMap())

        val report = ApkPatcher.patch(
            ApkPatcher.PatchRequest(
                sourceApk = src,
                outputApk = out,
                bundle = bundle,
                keystore = keystore(),
                minSdk = 24,
                keepAbi = "arm64-v8a",
            ),
        )

        assertTrue(
            "expected success; errors=${report.verification?.errors}",
            report.success,
        )
        assertTrue(report.verification?.verified == true)
        assertTrue(out.isFile && out.length() > 0)
        assertTrue("lib/arm64-v8a/libamxmodx.so" in report.addedEntries)

        val zip = ZipRaw.open(out)!!
        assertTrue("lib/arm64-v8a/libamxmodx.so" in zip.entries)
        zip.close()
    }

    @Test
    fun `patch removes meta inf and prunes other abi`() {
        val src = tmp.newFile("src.apk")
        src.writeBytes(fakeApk())
        val out = tmp.newFile("out.apk")

        val manifest = BundleManifest(version = "test", entries = emptyList())
        val bundle = Bundle(manifest, emptyMap(), emptyMap())

        ApkPatcher.patch(
            ApkPatcher.PatchRequest(
                sourceApk = src,
                outputApk = out,
                bundle = bundle,
                keystore = keystore(),
                minSdk = 24,
                keepAbi = "arm64-v8a",
            ),
        )

        val zip = ZipRaw.open(out)!!
        assertTrue("META-INF/AMXX-PAT.RSA" in zip.entries)
        assertTrue("lib/arm64-v8a/libyapb.so" in zip.entries)
        assertTrue("lib/armeabi-v7a/libyapb.so" !in zip.entries)
        zip.close()
    }
}
