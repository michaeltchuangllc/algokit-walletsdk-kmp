package com.michaeltchuang.walletsdk.seedvault.demo.ui.viewmodel

import com.michaeltchuang.walletsdk.seedvault.demo.data.FakeSeedVaultDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeedVaultViewModelTest {
    private lateinit var viewModel: SeedVaultViewModel

    private val seeds get() = viewModel.uiState.value.seeds

    private fun seed(id: Long) = viewModel.uiState.value.seed(id)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = SeedVaultViewModel(FakeSeedVaultDataSource())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun startsWithSampleSeeds() {
        assertEquals(FakeSeedVaultDataSource.sampleSeeds, seeds)
    }

    @Test
    fun addSeedAppendsUnbackedSeedWithNextId() {
        viewModel.addSeed()

        val added = seeds.last()
        assertEquals(3, seeds.size)
        assertEquals(3L, added.id)
        assertEquals("Demo Seed 3", added.name)
        assertFalse(added.isBackedUp)
    }

    @Test
    fun addSeedNeverReusesDeletedIds() {
        viewModel.deleteSeed(seedId = 2)
        viewModel.addSeed()

        assertEquals(listOf(1L, 3L), seeds.map { it.id })
    }

    @Test
    fun renameSeedTrimsAndIgnoresBlankNames() {
        viewModel.renameSeed(seedId = 1, name = "  Savings  ")
        assertEquals("Savings", seed(1)?.name)

        viewModel.renameSeed(seedId = 1, name = "   ")
        assertEquals("Savings", seed(1)?.name)
    }

    @Test
    fun deleteSeedRemovesIt() {
        viewModel.deleteSeed(seedId = 2)

        assertNull(seed(2))
        assertEquals(listOf(1L), seeds.map { it.id })
    }

    @Test
    fun revokeAppSkipsPrivilegedApps() {
        val apps = seed(2)!!.authorizedApps
        val privileged = apps.single { it.isPrivileged }
        val regular = apps.first { !it.isPrivileged }

        viewModel.revokeApp(seedId = 2, packageName = regular.packageName)
        viewModel.revokeApp(seedId = 2, packageName = privileged.packageName)

        val remaining = seed(2)!!.authorizedApps
        assertEquals(apps - regular, remaining)
    }
}
