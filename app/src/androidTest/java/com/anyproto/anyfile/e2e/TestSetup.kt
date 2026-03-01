package com.anyproto.anyfile.e2e

/**
 * Handles port forwarding from Android emulator to host machine.
 *
 * The Android emulator can access the host machine via the special IP 10.0.2.2.
 * This is the standard way for emulators to access services running on the host.
 *
 * After proper configuration:
 * - Coordinator: 10.0.2.2:1004 (host's coordinator)
 * - Filenode:   10.0.2.2:1005 (host's filenode)
 * - Nodes:      10.0.2.2:1001-1003 (host's nodes)
 *
 * This enables the Android emulator to access the any-sync infrastructure
 * running on the host machine via Docker Compose.
 *
 * Note: ADB reverse port forwarding is an alternative, but using 10.0.2.2
 * is simpler and more reliable for Android emulators.
 */
object EmulatorPortForwarding {

    /**
     * The special IP address that Android emulators use to refer to the host machine.
     * This is documented in the Android emulator documentation.
     */
    private const val HOST_IP = "10.0.2.2"

    /**
     * Flag to enable proxy mode for testing.
     * When true, connections go through the TLS proxy (port 6000).
     * When false, connections go directly to coordinator (port 1004).
     *
     * Set this before running tests:
     * ```kotlin
     * EmulatorPortForwarding.setUseProxy(true)
     * ```
     */
    @Volatile
    private var useProxy = true  // Enable proxy by default for E2E tests

    /**
     * Enable or disable proxy mode for testing.
     *
     * @param enabled true to connect through proxy (port 6000), false for direct connection (port 1004)
     */
    fun setUseProxy(enabled: Boolean) {
        useProxy = enabled
    }

    /**
     * Check if proxy mode is enabled.
     */
    fun isUsingProxy(): Boolean = useProxy

    /**
     * Get the coordinator host for use in emulator.
     * Returns the special IP 10.0.2.2 which refers to the host machine.
     */
    fun getCoordinatorHost(): String = HOST_IP

    /**
     * Get the coordinator port for use in emulator.
     * Returns the proxy port (6000) if proxy mode is enabled, otherwise the actual coordinator port (1004).
     */
    fun getCoordinatorPort(): Int = if (useProxy) 6100 else 1004

    /**
     * Get the coordinator address (host:port) for use in emulator.
     * Kept for backward compatibility with URL-based initialization.
     * @deprecated Use getCoordinatorHost() and getCoordinatorPort() instead.
     */
    @Deprecated("Use getCoordinatorHost() and getCoordinatorPort() instead", ReplaceWith("getCoordinatorHost()", "getCoordinatorPort()"))
    fun getCoordinatorAddress(): String = "http://$HOST_IP:1004"

    /**
     * Get the filenode host for use in emulator.
     * Returns the special IP 10.0.2.2 which refers to the host machine.
     */
    fun getFilenodeHost(): String = HOST_IP

    /**
     * Get the filenode port for use in emulator.
     * Returns the actual filenode port on the host machine.
     */
    fun getFilenodePort(): Int = 1005

    /**
     * Get the filenode address (host:port) for use in emulator.
     * Kept for backward compatibility with URL-based initialization.
     * @deprecated Use getFilenodeHost() and getFilenodePort() instead.
     */
    @Deprecated("Use getFilenodeHost() and getFilenodePort() instead", ReplaceWith("getFilenodeHost()", "getFilenodePort()"))
    fun getFilenodeAddress(): String = "http://$HOST_IP:1005"

    /**
     * Get the node addresses for use in emulator.
     * Returns list of (host, port) pairs.
     */
    fun getNodeAddresses(): List<Pair<String, Int>> = listOf(
        HOST_IP to 1001,
        HOST_IP to 1002,
        HOST_IP to 1003
    )

    /**
     * Setup port forwarding from emulator to host machine.
     * This is a no-op now since we use 10.0.2.2 directly.
     * Kept for backward compatibility.
     */
    fun setup() {
        // No-op - we use 10.0.2.2 directly
    }

    /**
     * Remove all port forwarding rules.
     * This is a no-op now since we use 10.0.2.2 directly.
     * Kept for backward compatibility.
     */
    fun cleanup() {
        // No-op - we use 10.0.2.2 directly
    }
}
