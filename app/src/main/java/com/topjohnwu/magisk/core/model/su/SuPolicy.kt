@file:SuppressLint("InlinedApi")
@file:Suppress("DEPRECATION")

package com.topjohnwu.magisk.core.model.su

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.model.su.SuPolicy.Companion.INTERACTIVE
import com.topjohnwu.magisk.core.utils.currentLocale
import com.topjohnwu.magisk.ktx.getLabel

data class SuPolicy(
    val uid: Int,
    val packageName: String,
    val appName: String,
    val icon: Drawable,
    var policy: Int = INTERACTIVE,
    var until: Long = -1L,
    val logging: Boolean = true,
    val notification: Boolean = true
) {

    companion object {
        const val INTERACTIVE = 0
        const val DENY = 1
        const val ALLOW = 2
    }

}

fun SuPolicy.toMap() = mapOf(
    "uid" to uid,
    "package_name" to packageName,
    "policy" to policy,
    "until" to until,
    "logging" to logging,
    "notification" to notification
)

@Throws(PackageManager.NameNotFoundException::class)
fun Map<String, String>.toPolicy(pm: PackageManager): SuPolicy {
    val uid = get("uid")?.toIntOrNull() ?: -1
    val packageName = get("package_name").orEmpty()
    var appName: String
    var icon: Drawable
    if (packageName.indexOf(':') != -1) {
        val name = packageName.split(':', limit = 2)
        val nowName = pm.getNameForUid(uid)
        if (nowName != packageName) throw PackageManager.NameNotFoundException()
        appName = "[SharedUser] ${name[0]}"
        icon = pm.defaultActivityIcon

        val pkgs = pm.getPackagesForUid(uid) ?: throw PackageManager.NameNotFoundException()
        for (p in pkgs) {
            val pi = pm.getPackageInfo(p, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            if (pi.sharedUserLabel == 0) break
            val res = pm.getResourcesForApplication(pi.applicationInfo)
            val config = Configuration().apply { setLocale(currentLocale) }
            res.updateConfiguration(config, res.displayMetrics)
            appName = "[SharedUser] ${res.getString(pi.sharedUserLabel)}"
            icon = pi.applicationInfo.loadIcon(pm)
            break
        }
    } else {
        val info = pm.getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        if (info.uid != uid) throw PackageManager.NameNotFoundException()
        appName = info.getLabel(pm)
        icon = info.loadIcon(pm)
    }
    return SuPolicy(
        uid = uid,
        packageName = packageName,
        appName = appName,
        icon = icon,
        policy = get("policy")?.toIntOrNull() ?: INTERACTIVE,
        until = get("until")?.toLongOrNull() ?: -1L,
        logging = get("logging")?.toIntOrNull() != 0,
        notification = get("notification")?.toIntOrNull() != 0
    )
}

@Throws(PackageManager.NameNotFoundException::class)
fun Int.toPolicy(pm: PackageManager,
                 policy: Int = INTERACTIVE,
                 transformUid: Boolean = false): SuPolicy {
    val pkg = pm.getNameForUid(this) ?: throw PackageManager.NameNotFoundException()
    val uid = if (transformUid) this % 100000 + Const.USER_ID * 100000 else this
    var appName: String
    var icon: Drawable
    if (pkg.indexOf(':') != -1) {
        val name = pkg.split(':', limit = 2)
        appName = "[SharedUser] ${name[0]}"
        icon = pm.defaultActivityIcon

        val pkgs = pm.getPackagesForUid(this) ?: throw PackageManager.NameNotFoundException()
        for (p in pkgs) {
            val pi = pm.getPackageInfo(p, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            if (pi.sharedUserLabel == 0) break
            val res = pm.getResourcesForApplication(pi.applicationInfo)
            val config = Configuration().apply { setLocale(currentLocale) }
            res.updateConfiguration(config, res.displayMetrics)
            appName = "[SharedUser] ${res.getString(pi.sharedUserLabel)}"
            icon = pi.applicationInfo.loadIcon(pm)
            break
        }
    } else {
        val info = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        appName = info.getLabel(pm)
        icon = info.loadIcon(pm)
    }
    return SuPolicy(
        uid = uid,
        packageName = pkg,
        appName = appName,
        icon = icon,
        policy = policy
    )
}

fun Int.toUidPolicy(pm: PackageManager, policy: Int): SuPolicy {
    return SuPolicy(
        uid = this,
        packageName = "[UID] $this",
        appName = "[UID] $this",
        icon = pm.defaultActivityIcon,
        policy = policy
    )
}
