package com.anyproto.anyfile.ui.screens.onboarding

import com.anyproto.anyfile.data.config.NetworkConfigRepository
import com.anyproto.anyfile.data.network.SyncClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var networkConfigRepo: NetworkConfigRepository
    private lateinit var syncClient: SyncClient
    private lateinit var viewModel: OnboardingViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        networkConfigRepo = mockk(relaxed = true)
        syncClient = mockk(relaxed = true)
        viewModel = OnboardingViewModel(networkConfigRepo, syncClient)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Initial`() {
        assertEquals(OnboardingState.Initial, viewModel.uiState.value)
    }

    @Test
    fun `importConfig with valid source transitions to ConfigLoaded`() = runTest {
        val fakeYaml = VALID_YAML.toByteArray()
        coEvery { networkConfigRepo.fetch(any()) } returns fakeYaml
        every { networkConfigRepo.save(any()) } returns Unit

        viewModel.importConfig("https://example.com/client.yml")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is OnboardingState.ConfigLoaded)
    }

    @Test
    fun `importConfig with network error transitions to Error`() = runTest {
        coEvery { networkConfigRepo.fetch(any()) } throws Exception("Network failure")

        viewModel.importConfig("https://bad.example.com/client.yml")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is OnboardingState.Error)
    }

    @Test
    fun `setSyncFolder stores path in repo`() {
        viewModel.setSyncFolder("/storage/emulated/0/AnyFile")
        verify { networkConfigRepo.syncFolderPath = "/storage/emulated/0/AnyFile" }
    }

    companion object {
        val VALID_YAML = """
nodes:
  - peerId: 12D3KooWABC
    addresses:
      - 10.0.2.2:1004
    types:
      - coordinator
""".trimIndent()
    }
}
