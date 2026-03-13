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
    }
}
