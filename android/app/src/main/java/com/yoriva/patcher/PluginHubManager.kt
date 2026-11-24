package com.yoriva.patcher

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class HubPlugin(
    val id: String,
    val name: String,
    val category: String,
    val version: String,
    val author: String,
    val description: String,
    val requiredModules: List<String>,
    val smaFileName: String,
    val amxxFileName: String,
    val sourceCode: String,
    val isInstalled: Boolean = false,
)

class PluginHubManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val compilerManager: CompilerManager,
    private val installPathProvider: () -> String,
) {
    private val _installedMap = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val installedMap: StateFlow<Map<String, Boolean>> = _installedMap.asStateFlow()

    private val _isInstalling = MutableStateFlow<String?>(null)
    val isInstalling: StateFlow<String?> = _isInstalling.asStateFlow()

    val catalog: List<HubPlugin> = listOf(
        HubPlugin(
            id = "resetscore",
            name = "ResetScore (/rs)",
            category = "Utility",
            version = "1.2",
            author = "Silenttt",
            description = "Allows players to reset their score and deaths to 0 by typing /rs or /resetscore in chat.",
            requiredModules = listOf("cstrike", "fun"),
            smaFileName = "resetscore.sma",
            amxxFileName = "resetscore.amxx",
            sourceCode = """
#include <amxmodx>
#include <cstrike>
#include <fun>

#define PLUGIN "ResetScore"
#define VERSION "1.2"
#define AUTHOR "Silenttt"

public plugin_init() {
    register_plugin(PLUGIN, VERSION, AUTHOR);
    register_clcmd("say /rs", "cmd_reset_score");
    register_clcmd("say /resetscore", "cmd_reset_score");
    register_clcmd("say_team /rs", "cmd_reset_score");
}

public cmd_reset_score(id) {
    if (!is_user_connected(id)) return PLUGIN_HANDLED;
    cs_set_user_deaths(id, 0);
    set_user_frags(id, 0);
    cs_set_user_deaths(id, 0);
    client_print(id, print_chat, "[Yoriva] Skolunuz basariyla sifirlandi!");
    return PLUGIN_HANDLED;
}
            """.trimIndent(),
        ),
        HubPlugin(
            id = "damager",
            name = "Damage Informer",
            category = "Combat",
            version = "1.0",
            author = "Yoriva",
            description = "Shows real-time floating damage numbers around crosshair when damaging an enemy.",
            requiredModules = listOf("cstrike"),
            smaFileName = "damager.sma",
            amxxFileName = "damager.amxx",
            sourceCode = """
#include <amxmodx>

#define PLUGIN "Damager"
#define VERSION "1.0"
#define AUTHOR "Yoriva"

new g_hud_sync;

public plugin_init() {
    register_plugin(PLUGIN, VERSION, AUTHOR);
    register_event("Damage", "event_damage", "b", "2!0", "3=0", "4!0");
    g_hud_sync = CreateHudSyncObj();
}

public event_damage(victim) {
    new attacker = get_user_attacker(victim);
    if (!is_user_connected(attacker) || attacker == victim) return;
    new damage = read_data(2);
    set_hudmessage(0, 100, 200, -1.0, 0.55, 0, 0.1, 1.5, 0.02, 0.02, -1);
    ShowSyncHudMsg(attacker, g_hud_sync, "%d", damage);
}
            """.trimIndent(),
        ),
        HubPlugin(
            id = "parachute",
            name = "Parachute for All",
            category = "Utility",
            version = "1.3",
            author = "KRoT@L",
            description = "Slows down falling speed when holding USE (E) key, eliminating fall damage completely.",
            requiredModules = listOf("engine", "cstrike"),
            smaFileName = "amx_parachute.sma",
            amxxFileName = "amx_parachute.amxx",
            sourceCode = """
#include <amxmodx>
#include <engine>

#define PLUGIN "Parachute"
#define VERSION "1.3"
#define AUTHOR "KRoTaL"

new bool:g_has_parachute[33];

public plugin_init() {
    register_plugin(PLUGIN, VERSION, AUTHOR);
}

public client_putinserver(id) {
    g_has_parachute[id] = true;
}

public client_PreThink(id) {
    if (!is_user_alive(id)) return;
    new button = get_user_button(id);
    if (button & IN_USE) {
        new Float:velocity[3];
        entity_get_vector(id, EV_VEC_velocity, velocity);
        if (velocity[2] < 0.0) {
            velocity[2] = (velocity[2] + 40.0 < -100.0) ? velocity[2] + 40.0 : -100.0;
            entity_set_vector(id, EV_VEC_velocity, velocity);
        }
    }
}
            """.trimIndent(),
        ),
        HubPlugin(
            id = "csdm",
            name = "CSDM (Deathmatch Core)",
            category = "Gamemode",
            version = "2.1",
            author = "BAILOPAN",
            description = "Turns CS 1.6 into fast-paced Deathmatch with instant respawn, weapon menu, and spawn protection.",
            requiredModules = listOf("cstrike", "hamsandwich", "fakemeta"),
            smaFileName = "csdm_core.sma",
            amxxFileName = "csdm_core.amxx",
            sourceCode = """
#include <amxmodx>
#include <hamsandwich>
#include <cstrike>

#define PLUGIN "CSDM Core Simple"
#define VERSION "2.1"
#define AUTHOR "BAILOPAN"

public plugin_init() {
    register_plugin(PLUGIN, VERSION, AUTHOR);
    RegisterHam(Ham_Killed, "player", "ham_player_killed_post", 1);
}

public ham_player_killed_post(victim) {
    set_task(1.5, "task_respawn", victim);
}

public task_respawn(id) {
    if (is_user_connected(id) && !is_user_alive(id)) {
        ExecuteHamB(Ham_CS_RoundRespawn, id);
    }
}
            """.trimIndent(),
        ),
        HubPlugin(
            id = "zombie_plague",
            name = "Zombie Plague Base",
            category = "Gamemode",
            version = "4.3",
            author = "MeRcyLeZZ",
            description = "Classic Zombie Plague mod: First zombie infection, human survivor defense, and custom classes.",
            requiredModules = listOf("cstrike", "hamsandwich", "fakemeta"),
            smaFileName = "zombie_plague_base.sma",
            amxxFileName = "zombie_plague_base.amxx",
            sourceCode = """
#include <amxmodx>
#include <cstrike>
#include <hamsandwich>

#define PLUGIN "Zombie Plague Base"
#define VERSION "4.3"
#define AUTHOR "MeRcyLeZZ"

public plugin_init() {
    register_plugin(PLUGIN, VERSION, AUTHOR);
}
            """.trimIndent(),
        ),
        HubPlugin(
            id = "admin_vip",
            name = "VIP Perk Menu",
            category = "Admin",
            version = "1.1",
            author = "Yoriva",
            description = "Gives VIP players free kevlar, extra HP, and a custom VIP weapon selection menu each round.",
            requiredModules = listOf("cstrike", "fun", "hamsandwich"),
            smaFileName = "vip_perks.sma",
            amxxFileName = "vip_perks.amxx",
            sourceCode = """
#include <amxmodx>
#include <fun>
#include <cstrike>
#include <hamsandwich>

#define PLUGIN "VIP Perks"
#define VERSION "1.1"
#define AUTHOR "Yoriva"

public plugin_init() {
    register_plugin(PLUGIN, VERSION, AUTHOR);
    RegisterHam(Ham_Spawn, "player", "ham_player_spawn_post", 1);
}

public ham_player_spawn_post(id) {
    if (is_user_alive(id) && (get_user_flags(id) & ADMIN_LEVEL_H)) {
        set_user_health(id, 115);
        cs_set_user_armor(id, 100, CS_ARMOR_VESTHELM);
        client_print(id, print_chat, "[Yoriva VIP] 115 HP ve 100 Zirh verildi!");
    }
}
            """.trimIndent(),
        ),
    )

    fun refreshInstalledStatus() {
        scope.launch(Dispatchers.IO) {
            val gameDir = File(installPathProvider())
            val pluginsDir = File(gameDir, "addons/amxmodx/plugins")
            val map = HashMap<String, Boolean>()
            for (p in catalog) {
                val exists = File(pluginsDir, p.amxxFileName).exists()
                map[p.id] = exists
            }
            _installedMap.value = map
        }
    }

    fun installPlugin(plugin: HubPlugin, onDone: (Boolean, String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            _isInstalling.value = plugin.id
            try {
                val gameDir = File(installPathProvider())
                val amxxDir = File(gameDir, "addons/amxmodx")
                val scriptDir = File(amxxDir, "scripting")
                val pluginsDir = File(amxxDir, "plugins")
                val configsDir = File(amxxDir, "configs")
                val pluginsIni = File(configsDir, "plugins.ini")

                scriptDir.mkdirs()
                pluginsDir.mkdirs()
                configsDir.mkdirs()

                // 1. Write the .sma source code
                val smaFile = File(scriptDir, plugin.smaFileName)
                smaFile.writeText(plugin.sourceCode)

                // 2. Compile on-device using CompilerManager
                val source = SmaSource(smaFile.absolutePath, smaFile.name, false)
                compilerManager.compile(source)

                // 3. Add to plugins.ini if not present
                val currentIni = if (pluginsIni.exists()) pluginsIni.readText() else ""
                if (!currentIni.contains(plugin.amxxFileName, ignoreCase = true)) {
                    val appendText = "\n${plugin.amxxFileName} ; ${plugin.name}\n"
                    pluginsIni.appendText(appendText)
                }

                refreshInstalledStatus()
                _isInstalling.value = null
                onDone(true, "${plugin.name} successfully installed & compiled!")
            } catch (t: Throwable) {
                _isInstalling.value = null
                onDone(false, t.message ?: "Installation failed")
            }
        }
    }

    fun uninstallPlugin(plugin: HubPlugin, onDone: (Boolean) -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val gameDir = File(installPathProvider())
                val amxxDir = File(gameDir, "addons/amxmodx")
                val pluginsDir = File(amxxDir, "plugins")
                val pluginsIni = File(amxxDir, "configs/plugins.ini")

                // Remove .amxx
                File(pluginsDir, plugin.amxxFileName).delete()

                // Remove from plugins.ini
                if (pluginsIni.exists()) {
                    val updated = pluginsIni.readLines()
                        .filterNot { it.contains(plugin.amxxFileName, ignoreCase = true) }
                        .joinToString("\n")
                    pluginsIni.writeText(updated)
                }

                refreshInstalledStatus()
                onDone(true)
            } catch (_: Throwable) {
                onDone(false)
            }
        }
    }
}
