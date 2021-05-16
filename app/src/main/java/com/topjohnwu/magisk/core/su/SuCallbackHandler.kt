package com.topjohnwu.magisk.core.su

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.topjohnwu.magisk.BuildConfig
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.intent
import com.topjohnwu.magisk.core.model.su.SuPolicy
import com.topjohnwu.magisk.core.model.su.toLog
import com.topjohnwu.magisk.core.model.su.toPolicy
import com.topjohnwu.magisk.core.model.su.toUidPolicy
import com.topjohnwu.magisk.di.ServiceLocator
import com.topjohnwu.magisk.ktx.startActivity
import com.topjohnwu.magisk.ktx.startActivityWithRoot
import com.topjohnwu.magisk.ui.surequest.SuRequestActivity
import com.topjohnwu.magisk.utils.Utils
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber

object SuCallbackHandler {

    const val REQUEST = "request"
    const val LOG = "log"
    const val NOTIFY = "notify"
    const val TEST = "test"

    operator fun invoke(context: Context, action: String?, data: Bundle?) {
        data ?: return

        // Debug messages
        if (BuildConfig.DEBUG) {
            Timber.d(action)
            data.let { bundle ->
                bundle.keySet().forEach {
                    Timber.d("[%s]=[%s]", it, bundle[it])
                }
            }
        }

        when (action) {
            REQUEST -> handleRequest(context, data)
            LOG -> handleLogging(context, data)
            NOTIFY -> handleNotify(context, data)
            TEST -> {
                val mode = data.getInt("mode", 2)
                Shell.su(
                    "magisk --connect-mode $mode",
                    "magisk --use-broadcast"
                ).submit()
            }
        }
    }

    private fun handleRequest(context: Context, data: Bundle) {
        val intent = context.intent<SuRequestActivity>()
            .setAction(REQUEST)
            .putExtras(data)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        if (Build.VERSION.SDK_INT >= 29) {
            // Android Q does not allow starting activity from background
            intent.startActivityWithRoot()
        } else {
            intent.startActivity(context)
        }
    }

    // https://android.googlesource.com/platform/frameworks/base/+/547bf5487d52b93c9fe183aa6d56459c170b17a4
    private fun Bundle.getIntComp(key: String, defaultValue: Int): Int {
        val value = get(key) ?: return defaultValue
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            else -> defaultValue
        }
    }

    private fun handleLogging(context: Context, data: Bundle) {
        val fromUid = data.getIntComp("from.uid", -1)
        val notify = data.getBoolean("notify", true)
        val allow = data.getIntComp("policy", SuPolicy.ALLOW)

        val pm = context.packageManager
        val policy = runCatching { fromUid.toPolicy(pm, allow) }.getOrElse {
            GlobalScope.launch { ServiceLocator.policyDB.delete(fromUid) }
            fromUid.toUidPolicy(pm, allow)
        }

        if (notify) notify(context, policy)

        val toUid = data.getIntComp("to.uid", -1)
        val pid = data.getIntComp("pid", -1)
        val command = data.getString("command", "")
        val log = policy.toLog(
            toUid = toUid,
            fromPid = pid,
            command = command
        )

        GlobalScope.launch {
            ServiceLocator.logRepo.insert(log)
        }
    }

    private fun handleNotify(context: Context, data: Bundle) {
        val fromUid = data.getIntComp("from.uid", -1)
        val allow = data.getIntComp("policy", SuPolicy.ALLOW)

        val pm = context.packageManager
        val policy = runCatching { fromUid.toPolicy(pm, allow) }.getOrElse {
            GlobalScope.launch { ServiceLocator.policyDB.delete(fromUid) }
            fromUid.toUidPolicy(pm, allow)
        }
        notify(context, policy)
    }

    private fun notify(context: Context, policy: SuPolicy) {
        if (Config.suNotification == Config.Value.NOTIFICATION_TOAST) {
            val resId = if (policy.policy == SuPolicy.ALLOW)
                R.string.su_allow_toast
            else
                R.string.su_deny_toast

            Utils.toast(context.getString(resId, policy.appName), Toast.LENGTH_SHORT)
        }
    }
}
