package com.anyproto.anyfile.data.config

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkConfigRepositoryTest {

    @get:Rule val tmpDir = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var repo: NetworkConfigRepository

    @Before
    fun setup() {
        context = mockk()
        prefs = mockk(relaxed = true)
        every { context.filesDir } returns tmpDir.root
        every { context.getSharedPreferences(any(), any()) } returns prefs
        repo = NetworkConfigRepository(context)
    }

    @Test
    fun `fetchFromLocalFile returns file bytes`() = runTest {
        val tmpFile = tmpDir.newFile("client.yml")
        tmpFile.writeText(VALID_CONFIG_YAML)
        val bytes = repo.fetch(tmpFile.absolutePath)
        assertTrue(bytes.isNotEmpty())
        assertEquals(VALID_CONFIG_YAML, String(bytes))
    }

    @Test
    fun `saveValidConfig writes file to config dir`() {
        repo.save(VALID_CONFIG_YAML.toByteArray())
        val saved = File(tmpDir.root, "network.yml")
        assertTrue(saved.exists())
        assertEquals(VALID_CONFIG_YAML, saved.readText())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `saveEmptyYaml throws IllegalArgumentException`() {
        repo.save("".toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `saveConfigWithNoCoordinatorThrows`() {
        repo.save(NO_COORDINATOR_YAML.toByteArray())
    }

    @Test
    fun `isConfiguredReturnsFalseWhenNoFile`() {
        assertFalse(repo.isConfigured())
    }

    @Test
    fun `isConfiguredReturnsTrueAfterSave`() {
        repo.save(VALID_CONFIG_YAML.toByteArray())
        assertTrue(repo.isConfigured())
    }

    @Test
    fun `getCoordinatorAddressReturnsFirstCoordinatorHostAndPort`() {
        repo.save(VALID_CONFIG_YAML.toByteArray())
        val (host, port) = repo.getCoordinatorAddress()
        assertEquals("10.0.2.2", host)
        assertEquals(1004, port)
    }

    @Test
    fun `getFilenodeAddressReturnsFirstFilenodeHostAndPort`() {
        repo.save(VALID_CONFIG_YAML.toByteArray())
        val (host, port) = repo.getFilenodeAddress()
        assertEquals("10.0.2.2", host)
        assertEquals(1005, port)
    }

    @Test
    fun `getFilenodeAddress works with 'file' type from real any-sync network yml`() {
        repo.save(REAL_NETWORK_YML.toByteArray())
        val (host, port) = repo.getFilenodeAddress()
        assertEquals("127.0.0.1", host)
        assertEquals(1005, port)
    }

    @Test
    fun `getCoordinatorAddress prefers plain IP over docker hostname when multiple addresses listed`() {
        repo.save(REAL_NETWORK_YML.toByteArray())
        val (host, port) = repo.getCoordinatorAddress()
        assertEquals("127.0.0.1", host)
        assertEquals(1004, port)
    }

    @Test
    fun `isConfigured returns true for addresses-first yaml format`() {
        repo.save(ADDRESSES_FIRST_NETWORK_YML.toByteArray())
        assertTrue(repo.isConfigured())
    }

    @Test
    fun `getCoordinatorAddress works with addresses-first yaml format`() {
        repo.save(ADDRESSES_FIRST_NETWORK_YML.toByteArray())
        val (host, port) = repo.getCoordinatorAddress()
        assertEquals("192.168.1.1", host)
        assertEquals(1004, port)
    }

    @Test
    fun `getFilenodeAddress works with addresses-first yaml format`() {
        repo.save(ADDRESSES_FIRST_NETWORK_YML.toByteArray())
        val (host, port) = repo.getFilenodeAddress()
        assertEquals("192.168.1.1", host)
        assertEquals(1005, port)
    }

    companion object {
        val VALID_CONFIG_YAML = """
nodes:
  - peerId: 12D3KooWABC
    addresses:
      - 10.0.2.2:1004
    types:
      - coordinator
  - peerId: 12D3KooWDEF
    addresses:
      - 10.0.2.2:1005
    types:
      - fileNode
""".trimIndent()

        val NO_COORDINATOR_YAML = """
nodes:
  - peerId: 12D3KooWDEF
    addresses:
      - 10.0.2.2:1005
    types:
      - fileNode
""".trimIndent()

        // Mirrors the real any-sync network.yml format: 'file' type, Docker hostname first
        val REAL_NETWORK_YML = """
nodes:
  - peerId: 12D3KooWJEj
    addresses:
      - any-sync-coordinator:1004
      - quic://any-sync-coordinator:1014
      - 127.0.0.1:1004
      - quic://127.0.0.1:1014
    types:
      - coordinator
  - peerId: 12D3KooWH9r
    addresses:
      - any-sync-filenode:1005
      - quic://any-sync-filenode:1015
      - 127.0.0.1:1005
      - quic://127.0.0.1:1015
    types:
      - file
""".trimIndent()

        // Actual any-sync client.yml format: addresses come BEFORE peerId
        val ADDRESSES_FIRST_NETWORK_YML = """
nodes:
  - addresses:
      - any-sync-coordinator:1004
      - quic://any-sync-coordinator:1014
      - 192.168.1.1:1004
      - quic://192.168.1.1:1014
    peerId: 12D3KooWJEj
    types:
      - coordinator
  - addresses:
      - any-sync-filenode:1005
      - quic://any-sync-filenode:1015
      - 192.168.1.1:1005
      - quic://192.168.1.1:1015
    peerId: 12D3KooWH9r
    types:
      - file
""".trimIndent()
    }
}
