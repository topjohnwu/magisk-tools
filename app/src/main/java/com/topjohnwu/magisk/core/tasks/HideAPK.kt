package com.topjohnwu.magisk.core.tasks

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.topjohnwu.magisk.BuildConfig.APPLICATION_ID
import com.topjohnwu.magisk.DynAPK
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.Provider
import com.topjohnwu.magisk.core.utils.AXML
import com.topjohnwu.magisk.core.utils.Keygen
import com.topjohnwu.magisk.di.ServiceLocator
import com.topjohnwu.magisk.ktx.writeTo
import com.topjohnwu.magisk.utils.APKInstall
import com.topjohnwu.magisk.utils.Utils
import com.topjohnwu.signing.JarMap
import com.topjohnwu.signing.SignApk
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.security.SecureRandom

object HideAPK {

    private const val ALPHA = "abcdefghijklmnopqrstuvwxyz"
    private const val ALPHADOTS = "$ALPHA....."
    private const val APP_NAME = "Magisk"
    private const val ANDROID_MANIFEST = "AndroidManifest.xml"

    // Some arbitrary limit
    const val MAX_LABEL_LENGTH = 32

    private val svc get() = ServiceLocator.networkService
    private val Context.APK_URI get() = Provider.APK_URI(packageName)
    private val Context.PREFS_URI get() = Provider.PREFS_URI(packageName)

    private fun genPackageName(): String {
        val random = SecureRandom()
        val len = 5 + random.nextInt(15)
        val builder = StringBuilder(len)
        var next: Char
        var prev = 0.toChar()
        for (i in 0 until len) {
            next = if (prev == '.' || i == 0 || i == len - 1) {
                ALPHA[random.nextInt(ALPHA.length)]
            } else {
                ALPHADOTS[random.nextInt(ALPHADOTS.length)]
            }
            builder.append(next)
            prev = next
        }
        if (!builder.contains('.')) {
            // Pick a random index and set it as dot
            val idx = random.nextInt(len - 2)
            builder[idx + 1] = '.'
        }
        return builder.toString()
    }

    fun patch(
        context: Context,
        apk: File, out: File,
        pkg: String, label: CharSequence
    ): Boolean {
        try {
            val jar = JarMap.open(apk, true)
            val je = jar.getJarEntry(ANDROID_MANIFEST)
            val xml = AXML(jar.getRawData(je))

            if (!xml.findAndPatch(APPLICATION_ID to pkg, APP_NAME to label.toString()))
                return false

            // Write apk changes
            jar.getOutputStream(je).write(xml.bytes)
            val keys = Keygen(context)
            SignApk.sign(keys.cert, keys.key, jar, FileOutputStream(out))
        } catch (e: Exception) {
            Timber.e(e)
            return false
        }

        return true
    }

    private class WaitPackageReceiver(
        private val pkg: String,
        activity: Activity
    ) : BroadcastReceiver() {

        private val activity = WeakReference(activity)

        private fun launchApp(): Unit = activity.get()?.run {
            val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
            Config.suManager = if (pkg == APPLICATION_ID) "" else pkg
            grantUriPermission(pkg, APK_URI, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            grantUriPermission(pkg, PREFS_URI, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.putExtra(Const.Key.PREV_PKG, packageName)
            startActivity(intent)
            finish()
        } ?: Unit

        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action ?: return) {
                Intent.ACTION_PACKAGE_REPLACED, Intent.ACTION_PACKAGE_ADDED -> {
                    val newPkg = intent.data?.encodedSchemeSpecificPart.orEmpty()
                    if (newPkg == pkg) {
                        context.unregisterReceiver(this)
                        launchApp()
                    }
                }
            }
        }

    }

    private suspend fun patchAndHide(activity: Activity, label: String): Boolean {
        val stub = File(activity.cacheDir, "stub.apk")
        try {
            svc.fetchFile(Info.remote.stub.link).byteStream().writeTo(stub)
        } catch (e: IOException) {
            Timber.e(e)
            return false
        }

        // Generate a new random package name and signature
        val repack = File(activity.cacheDir, "patched.apk")
        val pkg = genPackageName()
        Config.keyStoreRaw = ""

        if (!patch(activity, stub, repack, pkg, label))
            return false

        // Install and auto launch app
        APKInstall.registerInstallReceiver(activity, WaitPackageReceiver(pkg, activity))
        if (!Shell.su("adb_pm_install $repack").exec().isSuccess)
            APKInstall.installHideResult(activity, repack)
        return true
    }

    @Suppress("DEPRECATION")
    suspend fun hide(activity: Activity, label: String) {
        val dialog = android.app.ProgressDialog(activity).apply {
            setTitle(activity.getString(R.string.hide_app_title))
            isIndeterminate = true
            show()
        }
        val result = withContext(Dispatchers.IO) {
            patchAndHide(activity, label)
        }
        dialog.dismiss()
        if (!result) {
            Utils.toast(R.string.failure, Toast.LENGTH_LONG)
        }
    }

    @Suppress("DEPRECATION")
    fun restore(activity: Activity) {
        val dialog = android.app.ProgressDialog(activity).apply {
            setTitle(activity.getString(R.string.restore_img_msg))
            isIndeterminate = true
            show()
        }
        val apk = DynAPK.current(activity)
        APKInstall.registerInstallReceiver(activity, WaitPackageReceiver(APPLICATION_ID, activity))
        Shell.su("adb_pm_install $apk").submit {
            dialog.dismiss()
            if (!it.isSuccess)
                APKInstall.installHideResult(activity, apk)
        }
    }
}
