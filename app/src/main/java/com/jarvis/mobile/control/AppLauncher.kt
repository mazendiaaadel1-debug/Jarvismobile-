package com.jarvis.mobile.control

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo

/**
 * Opens an app given either an exact package id or a friendly/partial name.
 * The PC side (mobile_control.py) already maps common friendly names to
 * package ids, but this is the fallback the spec explicitly asks for:
 * "أضف طريقة لاكتشاف التطبيقات المثبتة أو التعامل مع package IDs" — so an
 * app the PC's static table doesn't know about can still be found here by
 * matching against what's actually installed on THIS phone.
 */
class AppLauncher(private val context: Context) {

    sealed class Result {
        data class Opened(val packageName: String, val label: String) : Result()
        object NotFound : Result()
    }

    fun open(nameOrPackage: String): Result {
        val pm = context.packageManager

        // 1) Exact package id — the common case, since the PC resolves
        //    friendly names to packages before sending the command.
        pm.getLaunchIntentForPackage(nameOrPackage)?.let { intent ->
            return launch(intent, nameOrPackage, labelFor(nameOrPackage))
        }

        // 2) Fuzzy match against installed apps' labels (installed-app
        //    discovery, per spec) — case-insensitive substring match.
        val needle = nameOrPackage.trim().lowercase()
        val installed = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
        val match = installed.firstOrNull { app ->
            labelFor(app.packageName).lowercase().contains(needle)
        }
        if (match != null) {
            pm.getLaunchIntentForPackage(match.packageName)?.let { intent ->
                return launch(intent, match.packageName, labelFor(match.packageName))
            }
        }

        return Result.NotFound
    }

    private fun launch(intent: Intent, packageName: String, label: String): Result {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return Result.Opened(packageName, label)
    }

    private fun labelFor(packageName: String): String {
        return try {
            val pm = context.packageManager
            val info: ApplicationInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}
