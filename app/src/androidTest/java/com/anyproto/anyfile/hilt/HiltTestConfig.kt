package com.anyproto.anyfile.hilt

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt test configuration for network components.
 *
 * This is a placeholder module for Hilt test configuration.
 * The actual network components are provided by the production
 * NetworkModule, which is correctly configured for both
 * production and test environments.
 *
 * Tests use @HiltAndroidTest with @Inject to get real instances
 * of the network components via dependency injection.
 */
@Module
@InstallIn(SingletonComponent::class)
object TestNetworkModule {
    // Production NetworkModule provides all network components
    // This module exists as a marker for test configuration
}
