package com.aiglasses.poc.connection

import kotlinx.coroutines.delay

class MockConnector : DeviceConnector {
    override suspend fun connect(): ConnectionState {
        delay(300)
        return ConnectionState.CONNECTED
    }

    override suspend fun disconnect(): ConnectionState {
        delay(100)
        return ConnectionState.DISCONNECTED
    }
}
