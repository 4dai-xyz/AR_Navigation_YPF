package com.aiglasses.poc.connection

interface DeviceConnector {
    suspend fun connect(): ConnectionState
    suspend fun disconnect(): ConnectionState
}
