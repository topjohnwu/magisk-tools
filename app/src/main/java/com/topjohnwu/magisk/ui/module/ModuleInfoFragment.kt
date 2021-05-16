package com.topjohnwu.magisk.ui.module

import android.os.Bundle
import android.view.View
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.BaseUIFragment
import com.topjohnwu.magisk.databinding.FragmentModuleInfoBinding
import com.topjohnwu.magisk.di.ServiceLocator
import com.topjohnwu.magisk.di.viewModel
import rikka.widget.borderview.BorderView

class ModuleInfoFragment : BaseUIFragment<ModuleInfoViewModel, FragmentModuleInfoBinding>() {

    override val layoutRes = R.layout.fragment_module_info
    override val viewModel by viewModel<ModuleInfoViewModel> {
        ModuleInfoViewModel(args, ServiceLocator.networkService)
    }
    private val args by lazy { ModuleInfoFragmentArgs.fromBundle(requireArguments()) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        activity.title = args.onlineModule.name

        if (!viewModel.showDownload) {
            binding.moduleInfoTextContainer.borderBottomVisibility = BorderView.BorderVisibility.NEVER
        }
    }
}
