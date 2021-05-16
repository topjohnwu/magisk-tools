package com.topjohnwu.magisk.core.su

import android.content.Intent
import android.content.pm.PackageManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import androidx.collection.ArrayMap
import com.topjohnwu.magisk.BuildConfig
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.magiskdb.PolicyDao
import com.topjohnwu.magisk.core.model.su.SuPolicy
import com.topjohnwu.magisk.core.model.su.toPolicy
import com.topjohnwu.magisk.ktx.now
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.SECONDS

class SuRequestHandler(
    private val pm: PackageManager,
    private val policyDB: PolicyDao
) : Closeable {

    private lateinit var output: DataOutputStream
    lateinit var policy: SuPolicy
        private set

    // Return true to indicate undetermined policy, require user interaction
    suspend fun start(intent: Intent): Boolean {
        if (!init(intent))
            return false

        // Never allow com.topjohnwu.magisk (could be malware)
        if (policy.packageName == BuildConfig.APPLICATION_ID)
            return false

        when (Config.suAutoResponse) {
            Config.Value.SU_AUTO_DENY -> {
                respond(SuPolicy.DENY, 0)
                return false
            }
            Config.Value.SU_AUTO_ALLOW -> {
                respond(SuPolicy.ALLOW, 0)
                return false
            }
        }

        return true
    }

    private suspend fun <T> Deferred<T>.timedAwait(): T? {
        return withTimeoutOrNull(SECONDS.toMillis(1)) {
            await()
        }
    }

    @Throws(IOException::class)
    override fun close() {
        if (::output.isInitialized)
            output.close()
    }

    private class SuRequestError : IOException()

    private suspend fun init(intent: Intent) = withContext(Dispatchers.IO) {
        try {
            val uid: Int
            if (Const.Version.atLeast_21_0()) {
                val name = intent.getStringExtra("fifo") ?: throw SuRequestError()
                uid = intent.getIntExtra("uid", -1).also { if (it < 0) throw SuRequestError() }
                output = DataOutputStream(FileOutputStream(name).buffered())
            } else {
                val name = intent.getStringExtra("socket") ?: throw SuRequestError()
                val socket = LocalSocket()
                socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
                output = DataOutputStream(BufferedOutputStream(socket.outputStream))
                val input = DataInputStream(BufferedInputStream(socket.inputStream))
                val map = async { input.readRequest() }.timedAwait() ?: throw SuRequestError()
                uid = map["uid"]?.toIntOrNull() ?: throw SuRequestError()
            }
            policy = uid.toPolicy(pm, transformUid = true)
            true
        } catch (e: Exception) {
            when (e) {
                is IOException, is PackageManager.NameNotFoundException -> {
                    Timber.e(e)
                    runCatching { close() }
                    false
                }
                else -> throw e  // Unexpected error
            }
        }
    }

    fun respond(action: Int, time: Int) {
        val until = if (time > 0)
            TimeUnit.MILLISECONDS.toSeconds(now) + TimeUnit.MINUTES.toSeconds(time.toLong())
        else
            time.toLong()

        policy.policy = action
        policy.until = until

        GlobalScope.launch(Dispatchers.IO) {
            try {
                output.writeInt(policy.policy)
                output.flush()
            } catch (e: IOException) {
                Timber.e(e)
            } finally {
                runCatching { close() }
                if (until >= 0)
                    policyDB.update(policy)
            }
        }
    }

    @Throws(IOException::class)
    private fun DataInputStream.readRequest(): Map<String, String> {
        fun readString(): String {
            val len = readInt()
            val buf = ByteArray(len)
            readFully(buf)
            return String(buf, Charsets.UTF_8)
        }

        val ret = ArrayMap<String, String>()
        while (true) {
            val name = readString()
            if (name == "eof")
                break
            ret[name] = readString()
        }
        return ret
    }

}
