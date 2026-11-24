package com.yoriva.lib
import java.io.File
object ApkPatcher {
    enum class Step { ANALYZE, INJECT, ALIGN, SIGN, VERIFY }
    data class Progress(
        val step: Step,
        val fraction: Float,
        val detail: String = "",
        val index: Int = 0,
        val total: Int = 0,
        val kind: String = "",
    )
    data class PatchRequest(
        val sourceApk: File,
        val outputApk: File,
        val bundle: Bundle,
        val keystore: SigningKeystore,
        val exclude: ExcludeRule = ExcludeRule.DEFAULT,
        val minSdk: Int = 21,
        val keepAbi: String? = null,
        val workers: Int = 1,
        val assetsZip: File? = null,
    )
    data class PatchReport(
        val sourceName: String,
        val sourceSizeBytes: Long,
        val sourceEntries: Int,
        val removedEntries: List<String>,
        val addedEntries: List<String>,
        val addedAssetFiles: Int = 0,
        val keptCount: Int,
        val alignedStored: Int,
        val padBytes: Long,
        val unsignedSizeBytes: Long,
        val signedSizeBytes: Long,
        val resolutionSeconds: Double,
        val arscStored: Boolean,
        val arscAligned: Boolean,
        val moduleLibs: List<String>,
        val patchedLibs: List<String>,
        val verification: ApkSignerTool.Verification?,
        val success: Boolean,
    ) {
        val sourceSizeMb: Double get() = sourceSizeBytes / 1048576.0
        val outputSizeMb: Double get() = signedSizeBytes / 1048576.0
    }
    fun patch(
        request: PatchRequest,
        onProgress: (Progress) -> Unit = {},
    ): PatchReport {
        val t0 = System.nanoTime()
        onProgress(Progress(Step.ANALYZE, 0.02f, "reading source archive"))
        val src = ZipRaw.open(request.sourceApk)
            ?: throw IllegalArgumentException("Source APK could not be parsed: ${request.sourceApk}")
        val sourceEntries = src.entries.size
        src.close()
        onProgress(Progress(Step.INJECT, 0.1f, "writing entries"))

        val tmpDir = request.outputApk.parentFile ?: java.io.File(System.getProperty("java.io.tmpdir"))
        val tmpUnsigned = java.io.File.createTempFile("yoriva-unsigned-", ".apk", tmpDir)
        try {
            val repack = ZipRepacker.repack(
                source = request.sourceApk,
                output = tmpUnsigned,
                bundle = request.bundle,
                exclude = request.exclude,
                pruneAbiExcept = request.keepAbi,
                extraAssetsZip = request.assetsZip,
                workers = request.workers,
                progress = { done, total ->
                    val p = 0.1f + 0.5f * (done.toFloat() / total.toFloat())
                },
                onEntry = { index, total, name, kind ->
                    val p = 0.1f + 0.5f * (index.toFloat() / total.coerceAtLeast(1).toFloat())
                    onProgress(Progress(Step.INJECT, p.coerceIn(0.1f, 0.6f), name, index + 1, total, kind))
                },
            )
            onProgress(Progress(Step.ALIGN, 0.62f, "aligning stored entries"))
            val out = ZipRaw.open(tmpUnsigned)
            val arsc = out?.entries?.get("resources.arsc")
            val arscStored = arsc != null && arsc.method == 0
            val arscAligned = arsc != null && (arsc.dataOffset % 4) == 0L
            val abi = request.keepAbi ?: "arm64-v8a"
            val abiPrefix = "lib/$abi/lib"
            val modSuffix = if (abi == "armeabi-v7a") "_amxx_arm.so" else "_amxx_amd64.so"
            val modules = repack.added.filter { it.startsWith(abiPrefix) && it.endsWith(modSuffix) }
                .plus(request.bundle.manifest.entries.map { it.target }.filter { it.startsWith(abiPrefix) && it.endsWith(modSuffix) })
                .distinct()
            val misaligned = out?.entries?.values?.filter {
                if (it.method != 0) {
                    false
                } else if (it.name.startsWith("lib/") && it.name.endsWith(".so")) {
                    (it.dataOffset % 16384) != 0L // Android 15+ 16KB page alignment requirement for ELF shared objects
                } else {
                    (it.dataOffset % 4) != 0L // 4-byte alignment for resources.arsc and other stored assets
                }
            }?.map { it.name } ?: emptyList()
            if (misaligned.isNotEmpty()) {
                throw IllegalArgumentException("Output has misaligned stored entries: $misaligned")
            }
            out?.close()

            onProgress(Progress(Step.SIGN, 0.68f, "signing v1 + v2"))
            val outcome = ApkSignerTool.sign(tmpUnsigned, request.outputApk, request.keystore, request.minSdk)
            onProgress(Progress(Step.VERIFY, 0.9f, "verifying signature"))
            val seconds = (System.nanoTime() - t0) / 1_000_000_000.0
            onProgress(Progress(Step.VERIFY, 1.0f, "verified"))
            return PatchReport(
                sourceName = request.sourceApk.name,
                sourceSizeBytes = request.sourceApk.length(),
                sourceEntries = sourceEntries,
                removedEntries = repack.removed,
                addedEntries = repack.added,
                addedAssetFiles = repack.addedAssets.size,
                keptCount = repack.kept.size,
                alignedStored = repack.alignedStored,
                padBytes = repack.padBytes,
                unsignedSizeBytes = repack.bytesWritten,
                signedSizeBytes = request.outputApk.length(),
                resolutionSeconds = seconds,
                arscStored = arscStored,
                arscAligned = arscAligned,
                moduleLibs = modules.sorted(),
                patchedLibs = repack.patchedLibs,
                verification = ApkSignerTool.Verification(
                    verified = outcome.verified,
                    signerFingerprintSha256 = outcome.signerFingerprintSha256,
                    signerFingerprintSha1 = "",
                    usedV1 = outcome.usedV1,
                    usedV2 = outcome.usedV2,
                    errors = outcome.errors,
                ),
                success = outcome.verified && arscStored && arscAligned,
            )
        } finally {
            runCatching { tmpUnsigned.delete() }
        }
    }
}