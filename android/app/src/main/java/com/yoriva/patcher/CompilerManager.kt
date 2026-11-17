package com.yoriva.patcher

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Environment
import com.yoriva.data.BundleProvider
import com.yoriva.lib.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Semaphore

data class SmaSource(val path: String, val name: String, val hasInclude: Boolean) {
    val scriptDir: String get() = File(path).parentFile?.absolutePath.orEmpty()
}

sealed interface CompileState {
    data object Idle : CompileState
    data class Compiling(val source: String) : CompileState
    data class Done(
        val log: String,
        val failures: Map<String, String> = emptyMap(),
        val ok: Int = 0,
        val total: Int = 0,
    ) : CompileState
    data class Failed(val message: String) : CompileState
}

class CompilerManager(
    private val app: Application,
    private val bundleProvider: BundleProvider,
    private val scope: CoroutineScope,
    private val abiProvider: () -> String,
    private val loadedBundleProvider: () -> Bundle?,
    private val installPathProvider: () -> String,
) {

    private val compilerPrefs = app.getSharedPreferences("compiler_prefs", Context.MODE_PRIVATE)

    private val _scripts = MutableStateFlow<List<SmaSource>>(emptyList())
    val scripts: StateFlow<List<SmaSource>> = _scripts.asStateFlow()

    private val _scriptRoot = MutableStateFlow<String?>(null)
    val scriptRoot: StateFlow<String?> = _scriptRoot.asStateFlow()

    private val _outputRoot = MutableStateFlow<String?>(null)
    val outputRoot: StateFlow<String?> = _outputRoot.asStateFlow()

    private val _compile = MutableStateFlow<CompileState>(CompileState.Idle)
    val compile: StateFlow<CompileState> = _compile.asStateFlow()

    private val _compileWorkers = MutableStateFlow(initialCompileWorkers())
    val compileWorkers: StateFlow<Int> = _compileWorkers.asStateFlow()

    private var scriptWatchJob: Job? = null



    init {
        autoFindAmxxFolder(false)
        autoFindAmxxFolder(true)
    }

    private fun initialCompileWorkers(): Int {
        val saved = compilerPrefs.getInt("compile_workers", 0)
        return if (saved > 0) saved.coerceIn(1, maxCompileWorkers()) else maxCompileWorkers()
    }

    fun maxCompileWorkers(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)

    fun effectiveCompileWorkers(): Int = _compileWorkers.value.coerceIn(1, maxCompileWorkers())

    fun setCompileWorkers(count: Int) {
        val clamped = count.coerceIn(1, maxCompileWorkers())
        if (clamped == _compileWorkers.value) return
        _compileWorkers.value = clamped
        compilerPrefs.edit().putInt("compile_workers", clamped).apply()
    }

    fun autoFindAmxxFolder(forOutput: Boolean): Boolean {
        val sub = if (forOutput) "plugins" else "scripting"
        val found = findAmxxFolder(sub) ?: return false
        if (forOutput) {
            _outputRoot.value = found.absolutePath
            compilerPrefs.edit().putString("output_root", found.absolutePath).apply()
        } else {
            _scriptRoot.value = found.absolutePath
            compilerPrefs.edit().putString("script_root", found.absolutePath).apply()
            _compile.value = CompileState.Idle
            refreshScripts()
        }
        return true
    }

    private fun findAmxxRoots(): List<File> =
        EngineProbe.gameDataRootCandidates(app).map(::File).filter { it.isDirectory }

    private fun findAmxxFolder(sub: String): File? {
        for (gamesRoot in findAmxxRoots()) {
            val games = ArrayList<String>()
            games.add(GAME_CSTRIKE)
            try {
                gamesRoot.listFiles()?.forEach { f ->
                    if (f.isDirectory && !games.contains(f.name)) games.add(f.name)
                }
            } catch (_: Exception) {
            }
            for (game in games) {
                val dir = File(File(File(gamesRoot, game), "addons/amxmodx"), sub)
                if (dir.isDirectory) return dir
            }
        }
        return null
    }


    fun failCompile(message: String) {
        _compile.value = CompileState.Failed(message)
    }

    fun refreshScripts() {
        scope.launch(Dispatchers.IO) {
            val dir = _scriptRoot.value?.let { File(it) } ?: run {
                _scripts.value = emptyList()
                return@launch
            }
            _scripts.value = readScriptFolder(dir)
        }
    }

    private fun readScriptFolder(dir: File): List<SmaSource> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f ->
            f.isFile && f.name.endsWith(".sma", ignoreCase = true)
        }?.sortedBy { it.name }?.map { f ->
            SmaSource(
                path = f.absolutePath,
                name = f.name,
                hasInclude = File(f.parentFile, "include").isDirectory,
            )
        }.orEmpty()
    }

    fun startScriptWatcher() {
        scriptWatchJob?.cancel()
        scriptWatchJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(SCRIPT_POLL_MS)
                if (_scriptRoot.value == null) continue
                val dir = File(_scriptRoot.value!!)
                val updated = readScriptFolder(dir)
                if (updated != _scripts.value) {
                    _scripts.value = updated
                }
            }
        }
    }

    fun stopScriptWatcher() {
        scriptWatchJob?.cancel()
        scriptWatchJob = null
    }

    fun compile(source: SmaSource) = compileAll(listOf(source))

    fun compileAll(sources: List<SmaSource>) {
        if (sources.isEmpty()) return
        if (_compile.value is CompileState.Compiling) return
        scope.launch(Dispatchers.IO) {
            val total = sources.size
            val scriptFolder = File(sources.first().path).parentFile
            val logDir = if (scriptFolder != null) File(scriptFolder, "logs") else null
            logDir?.mkdirs()
            val parallelism = effectiveCompileWorkers().coerceAtMost(total)
            val gate = Semaphore(parallelism)
            val finished = AtomicInteger(0)
            val results = arrayOfNulls<Pair<Boolean, String>>(total)
            _compile.value = CompileState.Compiling("Preparing compiler…")
            val preparedCompiler = try {
                prepareCompiler(scriptFolder ?: File("."))
            } catch (_: Throwable) {
                null
            }
            _compile.value = CompileState.Compiling("0/$total · ${sources.first().name}")
            kotlinx.coroutines.coroutineScope {
                sources.mapIndexed { index, source ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            val outcome = try {
                                compileOne(source, preparedCompiler)
                            } catch (t: Throwable) {
                                false to (t.message ?: "Compile error")
                            }
                            results[index] = outcome
                            val done = finished.incrementAndGet()
                            _compile.value = CompileState.Compiling(
                                "$done/$total · ${source.name}"
                            )
                        }
                    }
                }.awaitAll()
            }
            val log = StringBuilder()
            val failures = LinkedHashMap<String, String>()
            var failed = 0
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            for ((i, source) in sources.withIndex()) {
                val (ok, body) = results[i] ?: (false to "Compile did not run.")
                log
                    .append("── ${source.name} ──\n")
                    .append(body.trim().ifEmpty { if (ok) "Done." else "Compile failed." })
                    .append('\n')
                    .append('\n')
                val entry = "── ${source.name} ──\n${body.trim()}\n\n"
                val target = if (ok) {
                    logDir?.let { File(it, "compiler.log") }
                } else {
                    logDir?.let { File(it, "error.log") }
                }
                if (target != null) {
                    try {
                        target.appendText(
                            if (ok) entry
                            else "===== ${stamp.format(java.util.Date())} ${source.name} =====\n$entry"
                        )
                    } catch (_: Throwable) {
                    }
                }
                if (!ok) {
                    failed++
                    failures[source.path] = body.trim().ifEmpty { "Compile failed." }
                }
            }
            val okCount = total - failed
            val summary = "\n=== $okCount ok, $failed failed ($parallelism at a time) ==="
            _compile.value = CompileState.Done(
                log = log.toString() + summary,
                failures = failures,
                ok = okCount,
                total = total,
            )
        }
    }

    private fun compileOne(
        source: SmaSource,
        preparedCompiler: File? = null,
    ): Pair<Boolean, String> {
        val f = File(source.path)
        if (!f.exists()) error("Source not found: ${source.name}")
        val scriptDir = f.parentFile ?: error("Bad source path")
        val includeDir = File(scriptDir, "include")
        val amxxpc = preparedCompiler ?: prepareCompiler(scriptDir) ?: error(
            "Compiler (amxxpc) is missing from this build.\n" +
                "Install the latest Yoriva APK from the Releases page — it bundles the on-device compiler — then try again.\n" +
                "Alternatively, place an amxxpc binary next to your .sma files."
        )
        val cmd = mutableListOf(amxxpc.absolutePath)
        if (includeDir.isDirectory) {
            cmd.add("-i${includeDir.absolutePath}")
        }
        val gameDir = File(installPathProvider())
        val amxxPluginsDir = File(gameDir, "addons/amxmodx/plugins")
        val scriptDirName = scriptDir.name
        val scriptIsShipped = scriptDirName == "scripting" &&
            scriptDir.parentFile?.name == "amxmodx" &&
            gameDir.exists()
        val compiledDir = _outputRoot.value?.let { File(it) }?.takeIf { it.isDirectory }
            ?: if (scriptIsShipped) amxxPluginsDir else File(scriptDir, "compiled")
        compiledDir.mkdirs()
        val outPath = File(compiledDir, f.nameWithoutExtension + ".amxx").absolutePath
        cmd.add("-o$outPath")
        cmd.add(source.path)
        val compilerDir = amxxpc.parentFile
        val workDir = if (compilerDir != null && compilerDir.isDirectory) compilerDir else scriptDir
        val kernelBesideDriver = if (compilerDir != null) File(compilerDir, "amxxpc32.so") else null
        val filesKernel = File(compilerHome(), "amxxpc32.so")
        val kernelFound = (kernelBesideDriver != null && kernelBesideDriver.exists()) ||
            (filesKernel.exists() && matchesDeviceAbi(filesKernel))
        if (!kernelFound) {
            error(
                "Compiler kernel missing.\n" +
                    "Driver: ${amxxpc.absolutePath} (${amxxpc.length()} bytes)\n" +
                    "Looked in: ${kernelBesideDriver?.absolutePath ?: "(unknown)"} and ${filesKernel.absolutePath}\n" +
                    "Reinstall the app or re-download the bundle, then retry."
            )
        }
        val pb = ProcessBuilder(cmd).directory(workDir).redirectErrorStream(true)
        val ldDirs = listOfNotNull(
            compilerDir?.takeIf { it.isDirectory }?.absolutePath,
            compilerHome().takeIf { it.isDirectory }?.absolutePath,
        ).distinct()
        if (ldDirs.isNotEmpty()) {
            val oldLd = pb.environment()["LD_LIBRARY_PATH"]
            pb.environment()["LD_LIBRARY_PATH"] = (ldDirs + listOfNotNull(oldLd?.takeIf { it.isNotEmpty() })).joinToString(":")
        }
        if (!amxxpc.canExecute()) {
            try { Runtime.getRuntime().exec(arrayOf("chmod", "755", amxxpc.absolutePath)).waitFor() } catch (_: Throwable) {}
            amxxpc.setExecutable(true, false)
        }
        val process = pb.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        var ok = exit == 0
        val body = buildString {
            append(output.trim().ifEmpty { if (ok) "Done." else "Compile failed." })
            if (output.isBlank() && exit != 0) {
                val kernel = amxxpc.parentFile?.let { File(it, "amxxpc32.so") }
                append("\n[no output, exit=$exit, driver=${amxxpc.length()} bytes" +
                    (if (kernel != null && kernel.exists()) ", kernel=${kernel.length()} bytes" else ", no kernel") +
                    "]")
            }
            val out = File(compiledDir, f.nameWithoutExtension + ".amxx")
            if (exit != 0 || !out.exists()) {
                ok = false
                append("\nCompile failed.")
            }
        }
        return ok to body
    }

    private fun elfMachine(file: File): Int? = try {
        file.inputStream().use { ins ->
            val hdr = ByteArray(20)
            if (ins.read(hdr) < 20) return null
            if (!(hdr[0] == 0x7F.toByte() && hdr[1] == 'E'.code.toByte() &&
                    hdr[2] == 'L'.code.toByte() && hdr[3] == 'F'.code.toByte())
            ) return null
            ((hdr[19].toInt() and 0xFF) shl 8) or (hdr[18].toInt() and 0xFF)
        }
    } catch (_: Throwable) {
        null
    }

    private fun matchesDeviceAbi(file: File): Boolean {
        val machine = elfMachine(file) ?: return false
        val want64 = Build.SUPPORTED_ABIS.firstOrNull()?.contains("64") == true
        return if (want64) machine == 183 else machine == 40
    }

    private fun compilerHome(): File = File(app.filesDir, "compiler")

    private fun readCompilerStamp(): String? = try {
        File(compilerHome(), ".stamp").takeIf { it.exists() }?.readText()?.trim()
    } catch (_: Throwable) {
        null
    }

    private fun writeCompilerStamp(s: String) {
        try {
            compilerHome().mkdirs()
            File(compilerHome(), ".stamp").writeText(s)
        } catch (_: Throwable) {
        }
    }

    private fun appVersionCode(): Long = try {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (_: Throwable) {
        0L
    }

    private fun prepareCompiler(fallbackDir: File): File? {
        try {
            val nativeDir = File(app.applicationInfo.nativeLibraryDir)
            val nativeAmxxpc = File(nativeDir, "libamxxpc.so")
            val nativeKernel = File(nativeDir, "libamxxpc32.so")
            if (nativeAmxxpc.exists() && nativeAmxxpc.canExecute()) {
                try {
                    val dir = compilerHome()
                    dir.mkdirs()
                    val kernelCopy = File(dir, "amxxpc32.so")
                    val stamp = "native:${appVersionCode()}:${if (nativeKernel.exists()) nativeKernel.length() else -1}"
                    if (nativeKernel.exists() && (readCompilerStamp() != stamp || !matchesDeviceAbi(kernelCopy))) {
                        kernelCopy.delete()
                        kernelCopy.writeBytes(nativeKernel.readBytes())
                        try { Runtime.getRuntime().exec(arrayOf("chmod", "644", kernelCopy.absolutePath)).waitFor() } catch (_: Throwable) {}
                        kernelCopy.setReadable(true, false)
                        writeCompilerStamp(stamp)
                    }
                    if (!nativeKernel.exists() && !matchesDeviceAbi(kernelCopy)) {
                        val kb = try {
                            (loadedBundleProvider() ?: bundleProvider.loadCachedBundle(abiProvider()))
                                ?.files?.get("compiler/amxxpc32.so")
                        } catch (_: Throwable) {
                            null
                        }
                        if (kb != null && kb.isNotEmpty()) {
                            kernelCopy.delete()
                            kernelCopy.writeBytes(kb)
                            if (matchesDeviceAbi(kernelCopy)) {
                                try { Runtime.getRuntime().exec(arrayOf("chmod", "644", kernelCopy.absolutePath)).waitFor() } catch (_: Throwable) {}
                                kernelCopy.setReadable(true, false)
                                writeCompilerStamp("bundled-kernel:${kb.size}")
                            } else {
                                kernelCopy.delete()
                            }
                        }
                    }
                    return nativeAmxxpc
                } catch (_: Throwable) {
                }
            }
            val file = File(compilerHome(), "amxxpc")
            if (file.exists() && file.canExecute() && matchesDeviceAbi(file)) {
                return file
            }
            try {
                val b = loadedBundleProvider() ?: bundleProvider.loadCachedBundle(abiProvider()) ?: return null
                val driver = b.files["compiler/amxxpc"]
                val kernel = b.files["compiler/amxxpc32.so"]
                if (driver == null || kernel == null) return null
                val dir = compilerHome()
                dir.mkdirs()
                val driverOut = File(dir, "amxxpc")
                val kernelOut = File(dir, "amxxpc32.so")
                val driverStamp = "bundle:${driver.size}"
                if (readCompilerStamp() == driverStamp && driverOut.exists() && driverOut.canExecute() && matchesDeviceAbi(driverOut)) {
                    return driverOut
                }
                driverOut.delete()
                driverOut.writeBytes(driver)
                kernelOut.delete()
                kernelOut.writeBytes(kernel)
                try { Runtime.getRuntime().exec(arrayOf("chmod", "755", driverOut.absolutePath)).waitFor() } catch (_: Throwable) {}
                driverOut.setExecutable(true, false)
                writeCompilerStamp(driverStamp)
                return driverOut
            } catch (_: Throwable) {
                return null
            }
        } catch (_: Throwable) {
            return null
        }
    }

    companion object {
        private const val GAME_CSTRIKE = "cstrike"
            private const val SCRIPT_POLL_MS = 3_000L
    }
}
