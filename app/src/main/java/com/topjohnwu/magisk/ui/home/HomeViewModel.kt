package com.topjohnwu.magisk.ui.home

import androidx.databinding.Bindable
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.BuildConfig
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.*
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.download.Subject
import com.topjohnwu.magisk.core.download.Subject.Manager
import com.topjohnwu.magisk.data.repository.NetworkService
import com.topjohnwu.magisk.events.OpenInappLinkEvent
import com.topjohnwu.magisk.events.SnackbarEvent
import com.topjohnwu.magisk.events.dialog.EnvFixDialog
import com.topjohnwu.magisk.events.dialog.ManagerInstallDialog
import com.topjohnwu.magisk.events.dialog.UninstallDialog
import com.topjohnwu.magisk.ktx.await
import com.topjohnwu.magisk.utils.asText
import com.topjohnwu.magisk.utils.set
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.launch
import me.tatarka.bindingcollectionadapter2.BR
import kotlin.math.roundToInt

enum class MagiskState {
    NOT_INSTALLED, UP_TO_DATE, OBSOLETE, LOADING
}

class HomeViewModel(
    private val svc: NetworkService
) : BaseViewModel() {

    val magiskTitleBarrierIds =
        intArrayOf(R.id.home_magisk_icon, R.id.home_magisk_title, R.id.home_magisk_button)
    val magiskDetailBarrierIds =
        intArrayOf(R.id.home_magisk_installed_version, R.id.home_device_details_ramdisk)
    val appTitleBarrierIds =
        intArrayOf(R.id.home_manager_icon, R.id.home_manager_title, R.id.home_manager_button)

    @get:Bindable
    var isNoticeVisible = Config.safetyNotice
        set(value) = set(value, field, { field = it }, BR.noticeVisible)

    val stateMagisk = when {
        !Info.env.isActive -> MagiskState.NOT_INSTALLED
        Info.env.magiskVersionCode < BuildConfig.VERSION_CODE -> MagiskState.OBSOLETE
        else -> MagiskState.UP_TO_DATE
    }

    @get:Bindable
    var stateManager = MagiskState.LOADING
        set(value) = set(value, field, { field = it }, BR.stateManager)

    val magiskInstalledVersion get() = Info.env.run {
        if (isActive)
            "$magiskVersionString ($magiskVersionCode)".asText()
        else
            R.string.not_available.asText()
    }

    @get:Bindable
    var managerRemoteVersion = R.string.loading.asText()
        set(value) = set(value, field, { field = it }, BR.managerRemoteVersion)

    val managerInstalledVersion = Info.stub?.let {
        "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) (${it.version})"
    } ?: "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    @get:Bindable
    var stateManagerProgress = 0
        set(value) = set(value, field, { field = it }, BR.stateManagerProgress)

    @get:Bindable
    val showSafetyNet get() = Info.hasGMS && isConnected.get()

    val itemBinding = itemBindingOf<IconLink> {
        it.bindExtra(BR.viewModel, this)
    }

    private var shownDialog = false

    override fun refresh() = viewModelScope.launch {
        state = State.LOADING
        notifyPropertyChanged(BR.showSafetyNet)
        Info.getRemote(svc)?.apply {
            state = State.LOADED

            stateManager = when {
                BuildConfig.VERSION_CODE < magisk.versionCode -> MagiskState.OBSOLETE
                else -> MagiskState.UP_TO_DATE
            }

            managerRemoteVersion =
                "${magisk.version} (${magisk.versionCode}) (${stub.versionCode})".asText()

            launch {
                ensureEnv()
            }
        } ?: {
            state = State.LOADING_FAILED
            managerRemoteVersion = R.string.not_available.asText()
        }()
    }

    val showTest = false

    fun onTestPressed() = object : ViewEvent(), ActivityExecutor {
        override fun invoke(activity: BaseUIActivity<*, *>) {
            /* Entry point to trigger test events within the app */
        }
    }.publish()

    fun onProgressUpdate(progress: Float, subject: Subject) {
        if (subject is Manager)
            stateManagerProgress = progress.times(100f).roundToInt()
    }

    fun onLinkPressed(link: String) = OpenInappLinkEvent(link).publish()

    fun onDeletePressed() = UninstallDialog().publish()

    fun onManagerPressed() = when (state) {
        State.LOADED -> withExternalRW { ManagerInstallDialog().publish() }
        State.LOADING -> SnackbarEvent(R.string.loading).publish()
        else -> SnackbarEvent(R.string.no_connection).publish()
    }

    fun onMagiskPressed() = withExternalRW {
        HomeFragmentDirections.actionHomeFragmentToInstallFragment().navigate()
    }

    fun onSafetyNetPressed() =
        HomeFragmentDirections.actionHomeFragmentToSafetynetFragment().navigate()

    fun hideNotice() {
        Config.safetyNotice = false
        isNoticeVisible = false
    }

    private suspend fun ensureEnv() {
        val invalidStates = listOf(
            MagiskState.NOT_INSTALLED,
            MagiskState.LOADING
        )
        if (invalidStates.any { it == stateMagisk } || shownDialog) return

        val result = Shell.su("env_check ${Info.env.magiskVersionString} ${Info.env.magiskVersionCode}").await()
        if (!result.isSuccess) {
            shownDialog = true
            EnvFixDialog().publish()
        }
    }

}
