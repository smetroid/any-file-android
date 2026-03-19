package com.anyproto.anyfile.data.config

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for network configuration (network.yml / client.yml).
 *
 * Responsible for:
 * - Fetching config from local file path or HTTPS URL
 * - Validating that the config contains at least one coordinator node
 * - Persisting the config to filesDir/network.yml
 * - Providing coordinator and filenode addresses from the saved config
 * - Storing the chosen sync folder path in SharedPreferences
 */
@Singleton
class NetworkConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val CONFIG_FILE_NAME = "network.yml"
        private const val PREFS_NAME = "network_config_prefs"
        private const val KEY_SYNC_FOLDER = "sync_folder_path"
        private const val KEY_SPACE_ID = "space_id"
        private const val KEY_CID_PREFIX = "cid_"
        private const val MAX_CONFIG_SIZE = 1 * 1024 * 1024 // 1 MB
        private const val TIMEOUT_MS = 30_000
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val configFile: File
        get() = File(context.filesDir, CONFIG_FILE_NAME)

    /**
     * Sync folder path stored in SharedPreferences.
     */
    var syncFolderPath: String?
        get() = prefs.getString(KEY_SYNC_FOLDER, null)
        set(value) {
            prefs.edit().putString(KEY_SYNC_FOLDER, value).apply()
        }

    /**
     * Space ID for file sync, stored in SharedPreferences.
     */
    var spaceId: String?
        get() = prefs.getString(KEY_SPACE_ID, null)
        set(value) {
            prefs.edit().putString(KEY_SPACE_ID, value).apply()
        }

    /**
     * Returns the CID bytes for a file that was uploaded from this device, or null if unknown.
     */
    fun getCidForFile(fileId: String): ByteArray? {
        val base64 = prefs.getString("$KEY_CID_PREFIX$fileId", null) ?: return null
        return Base64.getDecoder().decode(base64)
    }

    /**
     * Stores the CID bytes for an uploaded file so it can be retrieved for download.
     */
    fun setCidForFile(fileId: String, cid: ByteArray) {
        val base64 = Base64.getEncoder().encodeToString(cid)
        prefs.edit().putString("$KEY_CID_PREFIX$fileId", base64).apply()
    }

    /**
     * Fetch config bytes from a local file path or an https:// URL.
     *
     * @param source Absolute file path or https:// URL
     * @return Config bytes (max 1 MB)
     * @throws IllegalArgumentException if the source is empty
     * @throws java.io.IOException on network/IO failure
     */
    suspend fun fetch(source: String): ByteArray = withContext(Dispatchers.IO) {
        require(source.isNotBlank()) { "Source must not be blank" }

        if (source.startsWith("https://") || source.startsWith("http://")) {
            fetchFromUrl(source)
        } else {
            fetchFromFile(source)
        }
    }

    private fun fetchFromFile(path: String): ByteArray {
        val file = File(path)
        require(file.exists()) { "File not found: $path" }
        require(file.length() <= MAX_CONFIG_SIZE) { "Config file too large (max 1 MB)" }
        return file.readBytes()
    }

    private fun fetchFromUrl(urlString: String): ByteArray {
        val url = URL(urlString)
        val connection = url.openConnection()
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        return connection.getInputStream().use { input ->
            val bytes = input.readBytes()
            require(bytes.size <= MAX_CONFIG_SIZE) { "Config from URL too large (max 1 MB)" }
            bytes
        }
    }

    /**
     * Validate and save config bytes to filesDir/network.yml.
     *
     * @param data Raw YAML bytes
     * @throws IllegalArgumentException if data is empty or contains no coordinator node
     */
    fun save(data: ByteArray) {
        require(data.isNotEmpty()) { "Config data must not be empty" }
        val yaml = String(data)
        require(parseNodes(yaml).any { it.types.contains("coordinator") }) {
            "Config must contain at least one coordinator node"
        }
        configFile.writeBytes(data)
    }

    /**
     * Returns true if a valid config file exists on disk.
     */
    fun isConfigured(): Boolean {
        if (!configFile.exists()) return false
        return try {
            val yaml = configFile.readText()
            parseNodes(yaml).any { it.types.contains("coordinator") }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns the first coordinator node's host and port.
     *
     * @throws IllegalStateException if no config is saved or no coordinator is found
     */
    fun getCoordinatorAddress(): Pair<String, Int> {
        check(configFile.exists()) { "No network config saved" }
        val yaml = configFile.readText()
        val node = parseNodes(yaml).firstOrNull { it.types.contains("coordinator") }
            ?: error("No coordinator node in config")
        return parseHostPort(selectAddress(node.addresses))
    }

    /**
     * Returns the first fileNode's host and port.
     * Accepts both "fileNode" (plan format) and "file" (real any-sync network.yml format).
     *
     * @throws IllegalStateException if no config is saved or no fileNode is found
     */
    fun getFilenodeAddress(): Pair<String, Int> {
        check(configFile.exists()) { "No network config saved" }
        val yaml = configFile.readText()
        val node = parseNodes(yaml)
            .firstOrNull { it.types.contains("fileNode") || it.types.contains("file") }
            ?: error("No fileNode in config")
        return parseHostPort(selectAddress(node.addresses))
    }

    /**
     * From a list of addresses, prefer a plain TCP IPv4 address (starts with a digit)
     * over Docker hostnames or quic:// URIs. Falls back to the first non-quic address,
     * then the raw first address if nothing better is found.
     */
    private fun selectAddress(addresses: List<String>): String {
        val tcpOnly = addresses.filterNot { it.startsWith("quic://") }
        return tcpOnly.firstOrNull { it.first().isDigit() } ?: tcpOnly.firstOrNull() ?: addresses.first()
    }

    // -------------------------------------------------------------------------
    // Manual YAML parser for the any-sync network.yml / client.yml format.
    // Supports both orderings:
    //   - peerId-first:   - peerId: 12D3KooW...
    //   - addresses-first: - addresses: [...], peerId: ..., types: [...]
    // -------------------------------------------------------------------------

    private data class NodeEntry(
        val peerId: String,
        val addresses: List<String>,
        val types: List<String>,
    )

    private fun parseNodes(yaml: String): List<NodeEntry> {
        val lines = yaml.lines()
        val nodes = mutableListOf<NodeEntry>()

        var inNodes = false
        var currentPeerId = ""
        val currentAddresses = mutableListOf<String>()
        val currentTypes = mutableListOf<String>()
        var section = "" // "addresses" or "types"

        fun flushNode() {
            if (currentPeerId.isNotEmpty()) {
                nodes.add(
                    NodeEntry(
                        peerId = currentPeerId,
                        addresses = currentAddresses.toList(),
                        types = currentTypes.toList(),
                    )
                )
            }
            currentPeerId = ""
            currentAddresses.clear()
            currentTypes.clear()
            section = ""
        }

        for (line in lines) {
            val trimmed = line.trimStart()

            when {
                line.trimEnd() == "nodes:" -> {
                    inNodes = true
                }
                inNodes && trimmed.startsWith("- peerId:") -> {
                    flushNode()
                    currentPeerId = trimmed.removePrefix("- peerId:").trim()
                }
                inNodes && trimmed.startsWith("peerId:") -> {
                    if (currentPeerId.isNotEmpty()) flushNode()
                    currentPeerId = trimmed.removePrefix("peerId:").trim()
                }
                inNodes && trimmed.startsWith("- addresses:") -> {
                    flushNode()
                    section = "addresses"
                }
                inNodes && trimmed.startsWith("addresses:") -> {
                    section = "addresses"
                }
                inNodes && trimmed.startsWith("types:") -> {
                    section = "types"
                }
                inNodes && trimmed.startsWith("- ") && section.isNotEmpty() -> {
                    val value = trimmed.removePrefix("- ").trim()
                    when (section) {
                        "addresses" -> currentAddresses.add(value)
                        "types" -> currentTypes.add(value)
                    }
                }
            }
        }

        flushNode()
        return nodes
    }

    private fun parseHostPort(address: String): Pair<String, Int> {
        val lastColon = address.lastIndexOf(':')
        require(lastColon > 0) { "Invalid address format: $address" }
        val host = address.substring(0, lastColon)
        val port = address.substring(lastColon + 1).toInt()
        return Pair(host, port)
    }
}
