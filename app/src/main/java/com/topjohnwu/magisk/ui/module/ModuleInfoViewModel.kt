package com.topjohnwu.magisk.ui.module

import android.content.res.Resources
import android.view.View
import androidx.databinding.Bindable
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.BaseViewModel
import com.topjohnwu.magisk.core.download.Action
import com.topjohnwu.magisk.core.download.DownloadService
import com.topjohnwu.magisk.core.download.Subject
import com.topjohnwu.magisk.data.repository.NetworkService
import com.topjohnwu.magisk.events.BackPressEvent
import com.topjohnwu.magisk.events.SnackbarEvent
import com.topjohnwu.magisk.utils.TextHolder
import com.topjohnwu.magisk.utils.set
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

class ModuleInfoViewModel(
        private val args: ModuleInfoFragmentArgs,
        private val svc: NetworkService
) : BaseViewModel() {

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                readmeText = TextHolder.String(svc.fetchString(args.onlineModule.notes_url))
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    @get:Bindable
    val showDownload = args.showDownload

    @get:Bindable
    var readmeText: TextHolder = TextHolder.Resource(R.string.module_loading_readme)
        set(value) = set(value, field, { field = it }, BR.readmeText)

    fun downloadPressed(view: View, install: Boolean) = if (isConnected.get()) withExternalRW {
        fun download(install: Boolean) {
            val config = if (install) Action.Flash else Action.Download
            val subject = Subject.Module(args.onlineModule, config)
            DownloadService.start(view.context, subject)
        }
        BackPressEvent().publish()
        download(install)
    } else {
        SnackbarEvent(R.string.no_connection).publish()
    }
}
