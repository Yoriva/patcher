package com.yoriva.lib
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
@Serializable
data class BundleManifest(
    val version: String = "",
    val game: String = "cs16client",
    val abi: String = "arm64-v8a",
    val entries: List<BundleEntry> = emptyList(),
) {
    @Serializable
    data class BundleEntry(
        val source: String,          
        val target: String,          
        val method: Compression = Compression.STORED,
        val required: Boolean = true,
        val description: String = "",
    )
    enum class Compression { STORED, DEFLATED }
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }
        fun parse(manifestJson: String): BundleManifest = json.decodeFromString(manifestJson)
        fun encode(manifest: BundleManifest): String = json.encodeToString(
            kotlinx.serialization.serializer<BundleManifest>(), manifest
        )
    }
}
@Serializable
data class ExcludeRule(
    val prefixes: List<String> = emptyList(),
    val exact: List<String> = emptyList(),
) {
    companion object {
        val DEFAULT = ExcludeRule(
            prefixes = listOf(
                "lib/arm64-v8a/libamxmodx.so",
                "lib/arm64-v8a/libmetadroid.so",
                "lib/arm64-v8a/libcstrike_amxx_amd64.so",
                "lib/arm64-v8a/libcsx_amxx_amd64.so",
                "lib/arm64-v8a/libengine_amxx_amd64.so",
                "lib/arm64-v8a/libfakemeta_amxx_amd64.so",
                "lib/arm64-v8a/libfun_amxx_amd64.so",
                "lib/arm64-v8a/libgeoip_amxx_amd64.so",
                "lib/arm64-v8a/libhamsandwich_amxx_amd64.so",
                "lib/arm64-v8a/libjson_amxx_amd64.so",
                "lib/arm64-v8a/libnvault_amxx_amd64.so",
                "lib/arm64-v8a/libreapi_amxx_amd64.so",
                "lib/arm64-v8a/libregex_amxx_amd64.so",
                "lib/arm64-v8a/libsockets_amxx_amd64.so",
                "lib/arm64-v8a/libsqlite_amxx_amd64.so",
                "lib/armeabi-v7a/libcstrike_amxx_arm.so",
                "lib/armeabi-v7a/libcsx_amxx_arm.so",
                "lib/armeabi-v7a/libengine_amxx_arm.so",
                "lib/armeabi-v7a/libfakemeta_amxx_arm.so",
                "lib/armeabi-v7a/libfun_amxx_arm.so",
                "lib/armeabi-v7a/libgeoip_amxx_arm.so",
                "lib/armeabi-v7a/libhamsandwich_amxx_arm.so",
                "lib/armeabi-v7a/libjson_amxx_arm.so",
                "lib/armeabi-v7a/libnvault_amxx_arm.so",
                "lib/armeabi-v7a/libreapi_amxx_arm.so",
                "lib/armeabi-v7a/libregex_amxx_arm.so",
                "lib/armeabi-v7a/libsockets_amxx_arm.so",
                "lib/armeabi-v7a/libsqlite_amxx_arm.so",
                "lib/armeabi-v7a/libcstrike_amxx_amd64.so",
                "lib/armeabi-v7a/libcsx_amxx_amd64.so",
                "lib/armeabi-v7a/libengine_amxx_amd64.so",
                "lib/armeabi-v7a/libfakemeta_amxx_amd64.so",
                "lib/armeabi-v7a/libfun_amxx_amd64.so",
                "lib/armeabi-v7a/libgeoip_amxx_amd64.so",
                "lib/armeabi-v7a/libhamsandwich_amxx_amd64.so",
                "lib/armeabi-v7a/libjson_amxx_amd64.so",
                "lib/armeabi-v7a/libnvault_amxx_amd64.so",
                "lib/armeabi-v7a/libreapi_amxx_amd64.so",
                "lib/armeabi-v7a/libregex_amxx_amd64.so",
                "lib/armeabi-v7a/libsockets_amxx_amd64.so",
                "lib/armeabi-v7a/libsqlite_amxx_amd64.so",
                "META-INF/",
            ),
            exact = emptyList(),
        )
    }
}
data class Bundle(
    val manifest: BundleManifest,
    val files: Map<String, ByteArray>,      
    val diskFiles: Map<String, java.io.File> = emptyMap(),
) {
    fun resolveEntry(e: BundleManifest.BundleEntry): ByteArray? =
        files[e.source] ?: diskFiles[e.source]
            ?.takeIf { it.isFile && it.exists() }
            ?.readBytes()
    fun withEntries(entries: List<BundleManifest.BundleEntry>): Bundle =
        Bundle(manifest.copy(entries = entries), files, diskFiles)
    companion object {
        private const val MANIFEST_PATH = "bundle.json"
        fun fromZip(zipBytes: ByteArray): Bundle {
            val file = ZipRaw.open(zipBytes) ?: error("Not a zip archive")
            try {
                val manifestEntry = file.entries.getValue(MANIFEST_PATH)
                val manifestBytes = file.readContent(manifestEntry)
                val manifest = BundleManifest.parse(manifestBytes.toString(Charsets.UTF_8))
                val files = HashMap<String, ByteArray>()
                for ((name, entry) in file.entries) {
                    if (name.endsWith("/")) continue
                    if (name == MANIFEST_PATH) continue
                    files[name] = file.readContent(entry)
                }
                return Bundle(manifest, files)
            } finally {
                file.close()
            }
        }
    }
}