package com.yoriva.patcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast

internal const val UPDATER_PREFS = "updater_prefs"
internal const val RELAUNCH_AFTER_UPDATE = "relaunch_after_update"

class AppInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_INSTALL_STATUS -> handleInstallStatus(context, intent)
            Intent.ACTION_MY_PACKAGE_REPLACED -> relaunchIfRequested(context)
        }
    }

    private fun handleInstallStatus(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirm?.let {
                    runCatching { context.startActivity(it) }
                        .onFailure { error -> Log.w(TAG, "Could not open installer", error) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                UpdateManager.notifyInstallStatus(PackageInstaller.STATUS_SUCCESS, null)
            }
            else -> {
                clearRelaunch(context)
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                UpdateManager.notifyInstallStatus(status, message)
                Toast.makeText(
                    context,
                    message?.takeIf { it.isNotBlank() } ?: "Update install failed",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun relaunchIfRequested(context: Context) {
        val prefs = context.getSharedPreferences(UPDATER_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(RELAUNCH_AFTER_UPDATE, false)) return
        prefs.edit().putBoolean(RELAUNCH_AFTER_UPDATE, false).apply()
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        runCatching { context.startActivity(launch) }
            .onFailure { error -> Log.w(TAG, "Could not relaunch after update", error) }
    }

    private fun clearRelaunch(context: Context) {
        context.getSharedPreferences(UPDATER_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(RELAUNCH_AFTER_UPDATE, false)
            .apply()
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.yoriva.patcher.INSTALL_STATUS"
        private const val TAG = "AppInstallReceiver"
    }
}
