package com.topjohnwu.magisk.core

import android.annotation.SuppressLint
import android.content.ContextWrapper
import android.content.Intent
import com.topjohnwu.magisk.core.base.BaseReceiver
import com.topjohnwu.magisk.di.ServiceLocator
import com.topjohnwu.magisk.view.Shortcuts
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

open class Receiver : BaseReceiver() {

    private val policyDB get() = ServiceLocator.policyDB

    @SuppressLint("InlinedApi")
    private fun getPkg(intent: Intent): String? {
        val pkg = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)
        return pkg ?: intent.data?.schemeSpecificPart
    }

    private fun getUid(intent: Intent): Int? {
        val uid = intent.getIntExtra(Intent.EXTRA_UID, -1)
        return if (uid == -1) null else uid
    }

    override fun onReceive(context: ContextWrapper, intent: Intent?) {
        intent ?: return

        fun rmPolicy(uid: Int) = GlobalScope.launch {
            policyDB.delete(uid)
        }

        when (intent.action ?: return) {
            Intent.ACTION_PACKAGE_REPLACED -> {
                // This will only work pre-O
                if (Config.suReAuth)
                    getUid(intent)?.let { rmPolicy(it) }
            }
            Intent.ACTION_UID_REMOVED -> {
                getUid(intent)?.let { rmPolicy(it) }
            }
            Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
                getPkg(intent)?.let { Shell.su("magiskhide rm $it").submit() }
            }
            Intent.ACTION_LOCALE_CHANGED -> Shortcuts.setupDynamic(context)
        }
    }
}
