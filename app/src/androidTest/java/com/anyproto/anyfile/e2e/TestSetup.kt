package com.anyproto.anyfile.e2e

/**
 * Handles port forwarding from Android emulator to host machine.
 *
 * This configuration uses ADB reverse port forwarding, which is the most
 * reliable method for Android emulators to access host machine services.
 *
 * Required ADB reverse setup (run once before tests):
 * ```bash
 * adb reverse tcp:6100 tcp:6100   # TLS proxy (if using proxy mode)
 * adb reverse tcp:11004 tcp:1004  # Coordinator (direct connection)
 * adb reverse tcp:11005 tcp:1005  # Filenode
 * ```
 *
 * Port mappings (with ADB reverse):
 * - Coordinator (via proxy): 127.0.0.1:6100 → host:6100 → host:1004
 * - Coordinator (direct): 127.0.0.1:11004 → host:1004
 * - Filenode: 127.0.0.1:11005 → host:1005
 *
 * Alternative: Use emulator IP 10.0.2.2 (requires emulator network config)
 *
 * This enables the Android emulator to access the any-sync infrastructure
 * running on the host machine via Docker Compose.
 */
object EmulatorPortForwarding {

    /**
     * The IP address to use for connecting to the host machine.
     *
     * When using ADB reverse port forwarding, we use "127.0.0.1" (localhost)
     * because adb reverse creates listeners on the device's localhost.
     *
     * Alternative: "10.0.2.2" (emulator's special IP for host access)
     *
     * Set this via useLocalhost() or useEmulatorIP() before running tests.
     */
    @Volatile
    private var hostIp: String = "127.0.0.1"  // Default to localhost for ADB reverse

    /**
     * Flag to enable proxy mode for testing.
     * When true, connections go through the TLS proxy (port 6100).
     * When false, connections go directly to coordinator (via adb reverse to 11004).
     *
     * The TLS translation proxy (port 6100) performs:
     * - TLS termination with Android clients (using standard X.509 certificates)
     * - Handshake translation (re-signs credentials with proxy's Ed25519 key)
     * - libp2p TLS connection to coordinator (using Ed25519 certificates)
     *
     * The proxy's peer ID is registered in the coordinator's network configuration,
     * allowing it to authenticate on behalf of clients.
     *
     * Set this before running tests:
     * ```kotlin
     * EmulatorPortForwarding.setUseProxy(true)  // Use proxy
     * EmulatorPortForwarding.setUseProxy(false) // Direct connection
     * ```
     */
    @Volatile
    private var useProxy = true  // Use proxy by default (peer ID registered in coordinator)

    /**
     * Use localhost (127.0.0.1) for connections.
     * This works when ADB reverse port forwarding is set up:
     * - adb reverse tcp:6100 tcp:6100  (proxy)
     * - adb reverse tcp:11004 tcp:1004 (coordinator)
     * - adb reverse tcp:11005 tcp:1005 (filenode)
     */
    fun useLocalhost() {
        hostIp = "127.0.0.1"
    }

    /**
     * Use emulator's special IP (10.0.2.2) for connections.
     * This requires the emulator network to be properly configured.
     */
    fun useEmulatorIP() {
        hostIp = "10.0.2.2"
    }

    /**
     * Enable or disable proxy mode for testing.
     *
     * @param enabled true to connect through proxy (port 6100), false for direct connection (port 11004)
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
     * Returns localhost when using ADB reverse, or 10.0.2.2 for emulator IP.
     */
    fun getCoordinatorHost(): String = hostIp

    /**
     * Get the coordinator port for use in emulator.
     * With proxy: returns 6100 (adb reverse → proxy → coordinator)
     * Without proxy: returns 11004 (adb reverse → coordinator directly)
     */
    fun getCoordinatorPort(): Int = if (useProxy) 6100 else 11004

    /**
     * Get the coordinator address (host:port) for use in emulator.
     * Kept for backward compatibility with URL-based initialization.
     * @deprecated Use getCoordinatorHost() and getCoordinatorPort() instead.
     */
    @Deprecated("Use getCoordinatorHost() and getCoordinatorPort() instead", ReplaceWith("getCoordinatorHost()", "getCoordinatorPort()"))
    fun getCoordinatorAddress(): String = "http://$hostIp:${if (useProxy) 6100 else 11004}"

    /**
     * Get the filenode host for use in emulator.
     * Returns localhost when using ADB reverse, or 10.0.2.2 for emulator IP.
     */
    fun getFilenodeHost(): String = hostIp

    /**
     * Get the filenode port for use in emulator.
     * Returns 11005 for ADB reverse (filenode directly).
     * Note: Filenode connections don't go through the proxy.
     */
    fun getFilenodePort(): Int = 11005

    /**
     * Get the filenode address (host:port) for use in emulator.
     * Kept for backward compatibility with URL-based initialization.
     * @deprecated Use getFilenodeHost() and getFilenodePort() instead.
     */
    @Deprecated("Use getFilenodeHost() and getFilenodePort() instead", ReplaceWith("getFilenodeHost()", "getFilenodePort()"))
    fun getFilenodeAddress(): String = "http://$hostIp:11005"

    /**
     * Get the node addresses for use in emulator.
     * Returns list of (host, port) pairs using ADB reverse ports.
     */
    fun getNodeAddresses(): List<Pair<String, Int>> = listOf(
        hostIp to 11001,
        hostIp to 11002,
        hostIp to 11003
    )

    /**
     * Setup ADB reverse port forwarding from emulator to host machine.
     *
     * This should be called before running tests, typically in @Before method.
     * Note: If port forwarding is already set up, this will silently fail (which is fine).
     */
    fun setup() {
        // ADB reverse is set up externally via command line
        // This method is kept for potential programmatic setup in the future
    }

    /**
     * Remove ADB reverse port forwarding rules.
     *
     * This can be called after tests are complete to clean up.
     */
    fun cleanup() {
        // ADB reverse cleanup is typically done manually via:
        // adb reverse --remove tcp:6100
        // adb reverse --remove-all
    }
}
