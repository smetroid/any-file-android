package com.anyproto.anyfile.ui.navigation

import androidx.lifecycle.ViewModel
import com.anyproto.anyfile.data.config.NetworkConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * ViewModel for navigation-level decisions.
 *
 * Determines whether the user has completed onboarding (config imported + coordinator present)
 * so that [AnyFileNavGraph] can choose the correct start destination.
 */
@HiltViewModel
class NavViewModel @Inject constructor(
    networkConfigRepository: NetworkConfigRepository,
) : ViewModel() {
    /** True when a valid network config has been saved to disk. Evaluated once at init time. */
    val isConfigured: Boolean = networkConfigRepository.isConfigured()
}
