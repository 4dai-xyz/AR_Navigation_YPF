package com.aiglasses.poc.nav

enum class NavState {
    OUTDOOR_IDLE,
    OUTDOOR_READY,
    OUTDOOR_ROUTE_READY,
    OUTDOOR_NAVIGATING,
    ENTRY_HANDOFF_PENDING,
    INDOOR_READY,
    INDOOR_CAPTURING,
    INDOOR_LOCATING,
    INDOOR_LOW_CONFIDENCE,
    INDOOR_ROUTING,
    INDOOR_ROUTE_READY,
    ERROR,
    ABORTED,
}

class DemoNavigationStateMachine {
    private var currentState: NavState = NavState.OUTDOOR_IDLE

    fun onOutdoorReady(): NavState {
        currentState = NavState.OUTDOOR_READY
        return currentState
    }

    fun onOutdoorRouteReady(): NavState {
        currentState = NavState.OUTDOOR_ROUTE_READY
        return currentState
    }

    fun onOutdoorNavigating(): NavState {
        currentState = NavState.OUTDOOR_NAVIGATING
        return currentState
    }

    fun onEntryHandoffPending(): NavState {
        currentState = NavState.ENTRY_HANDOFF_PENDING
        return currentState
    }

    fun onIndoorReady(): NavState {
        currentState = NavState.INDOOR_READY
        return currentState
    }

    fun onIndoorCaptureStarted(): NavState {
        currentState = NavState.INDOOR_CAPTURING
        return currentState
    }

    fun onIndoorLocateStarted(): NavState {
        currentState = NavState.INDOOR_LOCATING
        return currentState
    }

    fun onLocalizationStatus(status: String, hasStablePosition: Boolean): NavState {
        currentState = when (status) {
            "ok" -> if (hasStablePosition) NavState.INDOOR_READY else NavState.ERROR
            "low_confidence" -> NavState.INDOOR_LOW_CONFIDENCE
            else -> NavState.ERROR
        }
        return currentState
    }

    fun onIndoorRouteStarted(): NavState {
        currentState = NavState.INDOOR_ROUTING
        return currentState
    }

    fun onIndoorRouteReady(): NavState {
        currentState = NavState.INDOOR_ROUTE_READY
        return currentState
    }

    fun onError(): NavState {
        currentState = NavState.ERROR
        return currentState
    }

    fun onAbort(): NavState {
        currentState = NavState.ABORTED
        return currentState
    }

    fun onReset(): NavState {
        currentState = NavState.OUTDOOR_IDLE
        return currentState
    }

    fun current(): NavState = currentState
}
