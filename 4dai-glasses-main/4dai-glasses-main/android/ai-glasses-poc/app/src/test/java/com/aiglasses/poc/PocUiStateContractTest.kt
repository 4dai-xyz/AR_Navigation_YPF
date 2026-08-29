package com.aiglasses.poc

import com.aiglasses.poc.nav.NavState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PocUiStateContractTest {
    @Test
    fun outdoorRouteReadyShowsOverviewAndExternalNavigation() {
        val state = PocUiState(
            navState = NavState.OUTDOOR_ROUTE_READY,
            uiSignals = UiSignals(amapReady = true),
        ).withUiContract()

        assertEquals(DemoPhase.OUTDOOR, state.topCard.phase)
        assertTrue(state.mapChrome.showRecenter)
        assertTrue(state.mapChrome.showOrientationToggle)
        assertTrue(state.mapChrome.showOverview)
        assertTrue(state.bottomBar.showStartNavigation)
        assertTrue(state.bottomBar.showExternalNavigation)
    }

    @Test
    fun outdoorPrepareKeepsManualEnterVenueAvailable() {
        val state = PocUiState(
            navState = NavState.OUTDOOR_IDLE,
            uiSignals = UiSignals(amapReady = true),
        ).withUiContract()

        assertEquals(DemoPhase.OUTDOOR, state.topCard.phase)
        assertTrue(state.bottomBar.showPrepareRoute)
        assertTrue(state.bottomBar.showExternalNavigation)
        assertTrue(state.bottomBar.showEnterVenue)
        assertFalse(state.bottomBar.showManualIndoorControls)
    }

    @Test
    fun indoorStateDoesNotShowOutdoorHeadingToggle() {
        val state = PocUiState(navState = NavState.INDOOR_READY).withUiContract()

        assertEquals(DemoPhase.INDOOR, state.topCard.phase)
        assertTrue(state.mapChrome.showRecenter)
        assertFalse(state.mapChrome.showOrientationToggle)
        assertFalse(state.mapChrome.showOverview)
        assertFalse(state.bottomBar.showPrepareRoute)
        assertFalse(state.bottomBar.showExternalNavigation)
        assertTrue(state.bottomBar.showManualIndoorControls)
        assertFalse(state.bottomBar.showCaptureAndLocate)
        assertFalse(state.bottomBar.showRequestRoute)
        assertTrue(state.topCard.detail.contains("manual_demo"))
    }

    @Test
    fun lowConfidenceWarningIsVisibleOnTopCard() {
        val state = PocUiState(
            navState = NavState.INDOOR_LOW_CONFIDENCE,
            lowConfidenceSummary = "低置信度：已模拟",
            uiSignals = UiSignals(lowConfidenceActive = true),
        ).withUiContract()

        assertEquals("低置信度：已模拟", state.topCard.warning)
        assertTrue(state.topCard.showLowConfidence)
        assertNull(state.topCard.error)
    }

    @Test
    fun errorSummaryIsVisibleOnTopCard() {
        val state = PocUiState(
            navState = NavState.ERROR,
            errorSummary = "定位失败：timeout",
        ).withUiContract()

        assertEquals(DemoPhase.ERROR, state.topCard.phase)
        assertEquals("定位失败：timeout", state.topCard.error)
        assertFalse(state.mapChrome.showRecenter)
        assertTrue(state.bottomBar.showExternalNavigation)
    }
}
