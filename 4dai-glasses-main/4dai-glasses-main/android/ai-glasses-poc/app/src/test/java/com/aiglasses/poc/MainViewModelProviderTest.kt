package com.aiglasses.poc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelProviderTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun fallbackSelectedProviderMovesToNextProviderAndShowsReason() = runTest(dispatcher) {
        val viewModel = MainViewModel()
        advanceUntilIdle()

        viewModel.fallbackSelectedProvider("模拟图像来源失败")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("glasses_thumbnail", state.selectedProviderId)
        assertTrue(state.uiSignals.fallbackActive)
        assertEquals("glasses_album_sync", state.uiSignals.fallbackFromProviderId)
        assertEquals("glasses_thumbnail", state.uiSignals.fallbackToProviderId)
        assertEquals("glasses_album_sync", state.lastFailedProviderId)
        assertTrue(state.lastProviderFailureReason.orEmpty().contains("模拟图像来源失败"))
        assertTrue(state.topCard.warning.orEmpty().contains("降级"))
    }

    @Test
    fun secondFallbackReachesPhoneCameraFallback() = runTest(dispatcher) {
        val viewModel = MainViewModel()
        advanceUntilIdle()

        viewModel.fallbackSelectedProvider("album failed")
        viewModel.fallbackSelectedProvider("thumbnail failed")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(MainViewModel.PHONE_PROVIDER_ID, state.selectedProviderId)
        assertEquals("glasses_thumbnail", state.uiSignals.fallbackFromProviderId)
        assertEquals(MainViewModel.PHONE_PROVIDER_ID, state.uiSignals.fallbackToProviderId)
        assertEquals("glasses_thumbnail", state.lastFailedProviderId)
    }

    @Test
    fun lowConfidenceSimulationUpdatesWarningContract() = runTest(dispatcher) {
        val viewModel = MainViewModel()
        advanceUntilIdle()

        viewModel.simulateLowConfidence()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("low_confidence", state.lastLocalizationStatus)
        assertTrue(state.uiSignals.lowConfidenceActive)
        assertTrue(state.topCard.warning.orEmpty().contains("低置信度"))
    }

    @Test
    fun indoorBasemapStatusIsStoredForDebugSummary() = runTest(dispatcher) {
        val viewModel = MainViewModel()
        advanceUntilIdle()

        viewModel.onIndoorBasemapChanged(
            IndoorBasemapUiModel(
                enabled = true,
                available = true,
                activePoiId = "amap_demo_building",
                activeFloorName = "F1",
                statusSummary = "高德室内底图：已开启 floor=F1 poiid=amap_demo_building",
            ),
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.indoorBasemap.enabled)
        assertTrue(state.indoorBasemap.available)
        assertEquals("F1", state.indoorBasemap.activeFloorName)
        assertTrue(state.logs.any { it.contains("高德室内底图") })
    }
}
