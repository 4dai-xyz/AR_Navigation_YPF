package com.aiglasses.poc.network

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

class PcBackendAutoDiscoveryClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(500, TimeUnit.MILLISECONDS)
        .readTimeout(700, TimeUnit.MILLISECONDS)
        .build()

    fun discover(): String? {
        val candidates = buildCandidates()
        if (candidates.isEmpty()) return null
        val executor = Executors.newFixedThreadPool(DISCOVERY_PARALLELISM)
        val completion = ExecutorCompletionService<String?>(executor)
        return try {
            candidates.forEach { candidate ->
                completion.submit(Callable { candidate.takeIf(::probe) })
            }
            repeat(candidates.size) {
                val found = completion.poll(DISCOVERY_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)?.get()
                if (found != null) return found
            }
            null
        } finally {
            executor.shutdownNow()
        }
    }

    private fun probe(baseUrl: String): Boolean {
        return runCatching {
            val request = Request.Builder()
                .url("$baseUrl/api/v1/health")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                response.isSuccessful &&
                    body.contains("indoor-navigation-api") &&
                    body.contains("venue_exhibition_demo")
            }
        }.getOrDefault(false)
    }

    private fun buildCandidates(): List<String> {
        val candidates = linkedSetOf<String>()
        localIpv4Addresses().forEach { localIp ->
            val prefix = localIp.substringBeforeLast('.', missingDelimiterValue = "")
            if (prefix.isBlank()) return@forEach
            for (host in 1..254) {
                val candidateIp = "$prefix.$host"
                if (candidateIp != localIp) {
                    candidates += "http://$candidateIp:$DEFAULT_PORT"
                }
            }
        }
        COMMON_SUBNET_PREFIXES.forEach { prefix ->
            for (host in 1..254) {
                candidates += "http://$prefix.$host:$DEFAULT_PORT"
            }
        }
        return candidates.toList()
    }

    private fun localIpv4Addresses(): List<String> {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { networkInterface -> networkInterface.isUp && !networkInterface.isLoopback }
                .flatMap { networkInterface -> networkInterface.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { address -> !address.isLoopbackAddress && !address.isLinkLocalAddress }
                .map { address -> address.hostAddress.orEmpty() }
                .filter { address -> address.isNotBlank() }
                .distinct()
                .toList()
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val DEFAULT_PORT = 8000
        private const val DISCOVERY_PARALLELISM = 24
        private const val DISCOVERY_POLL_TIMEOUT_MS = 350L
        private val COMMON_SUBNET_PREFIXES = listOf(
            "192.168.50",
            "192.168.43",
            "192.168.49",
            "192.168.137",
            "192.168.0",
            "192.168.1",
            "10.0.0",
            "172.20.10",
        )
    }
}
