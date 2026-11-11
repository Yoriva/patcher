package com.pickle.patcher.patcher

import android.app.Application
import java.io.File

object EngineProbe {
    const val ENGINE_TEST = "su.xash.engine.test"
    const val ENGINE_RELEASE = "su.xash.engine"
    const val ENGINE_PLAY = "in.celest.xash3d"
    const val CLIENT_TEST = "su.xash.cs16client.test"
    const val CLIENT_RELEASE = "su.xash.cs16client"
    const val CLIENT_NEXORA = "su.xash.cs16client.nexora"
    const val CLIENT_PLAY = "in.celest.xash3d.cs16client"

    private val enginePackages = listOf(ENGINE_TEST, ENGINE_RELEASE, ENGINE_PLAY)
    private val clientPackages = listOf(CLIENT_NEXORA, CLIENT_TEST, CLIENT_RELEASE, CLIENT_PLAY)
    private val defaultRoots = listOf(
        "/storage/emulated/0/.xash",
        "/storage/emulated/0/xash",
        "/sdcard/.xash",
        "/sdcard/xash",
    )

    fun isEngineInstalled(app: Application): Boolean =
        enginePackages.any { isInstalled(app, it) }

    fun isClientInstalled(app: Application): Boolean =
        clientPackages.any { isInstalled(app, it) }

    fun isGameInstalled(app: Application): Boolean =
        isEngineInstalled(app) && isClientInstalled(app)

    private fun isInstalled(app: Application, pkg: String): Boolean = runCatching {
        app.packageManager.getPackageInfo(pkg, 0).versionCode
        true
    }.getOrDefault(false)

    private fun engineLogRoot(): String? = runCatching {
        defaultRoots.firstNotNullOfOrNull { base ->
            val file = File(base, "engine.log")
            if (!file.isFile) return@firstNotNullOfOrNull null
            val lines = file.readLines()
            lines.asReversed().firstOrNull { it.contains("is working directory now") }
                ?.substringBefore(" is working directory now")
                ?.trim()
                ?.takeIf { it.isNotBlank() && File(it).isDirectory }
        }
    }.getOrNull()

    fun gameDataRootCandidates(app: Application): List<String> {
        val roots = mutableListOf<String>()
        engineLogRoot()?.let { roots += it }
        defaultRoots.forEach { if (it !in roots) roots += it }
        return roots
    }

    fun resolveGameRoot(app: Application, preferredGame: String): String {
        for (root in gameDataRootCandidates(app)) {
            if (isGameDirInstalled(File(root, preferredGame))) return root
        }
        for (root in gameDataRootCandidates(app)) {
            for (game in listOf(
                com.pickle.patcher.patcher.PatcherViewModel.GAME_CSTRIKE,
                com.pickle.patcher.patcher.PatcherViewModel.GAME_CZERO,
            )) {
                if (isGameDirInstalled(File(root, game))) return root
            }
        }
        return gameDataRootCandidates(app).first()
    }

    fun isGameDirInstalled(dir: File): Boolean =
        dir.isDirectory && (
            File(dir, "config.cfg").isFile ||
            File(dir, "dlls").isDirectory ||
            File(dir, "cl_dlls").isDirectory ||
            File(dir, "maps").isDirectory ||
            File(dir, "valve.rc").isFile
        )

    fun gameDataPresent(app: Application): Boolean =
        gameDataRootCandidates(app).any { root ->
            listOf(
                com.pickle.patcher.patcher.PatcherViewModel.GAME_CSTRIKE,
                com.pickle.patcher.patcher.PatcherViewModel.GAME_CZERO,
            ).any { game -> isGameDirInstalled(File(root, game)) }
        }
}
