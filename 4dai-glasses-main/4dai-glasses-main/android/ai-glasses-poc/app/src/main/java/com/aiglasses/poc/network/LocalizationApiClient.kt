package com.aiglasses.poc.network

import com.aiglasses.poc.image.CapturedFrame
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class ServiceHealthResult(
    val requestId: String,
    val status: String,
    val service: String,
    val version: String,
    val serviceMode: String?,
    val recognitionMode: String?,
    val venueId: String?,
    val landmarkCount: Int?,
    val algorithmBackendSummary: String?,
)

data class PcBackendPairingResult(
    val type: String,
    val version: Int,
    val service: String,
    val baseUrl: String,
    val candidateBaseUrls: List<String>,
    val healthUrl: String?,
    val visualLocateUrl: String?,
    val debugVisualUrl: String?,
    val recentRequestsUrl: String?,
    val venueId: String?,
    val serviceMode: String?,
    val recognitionMode: String?,
    val preferredCaptureMode: String?,
)

data class PcBackendPairingConnectionResult(
    val pairing: PcBackendPairingResult,
    val selectedBaseUrl: String,
    val health: ServiceHealthResult,
)

data class VenueMetaResult(
    val requestId: String,
    val venueId: String,
    val venueName: String,
    val defaultFloorId: String,
    val supportedFloors: List<String>,
    val targetPoiCount: Int,
    val packageVersion: String,
    val pois: List<VenuePoiResult>,
    val routeNodes: List<VenueRouteNodeResult>,
)

data class VenuePoiResult(
    val poiId: String,
    val displayName: String,
    val floorId: String,
    val x: Double,
    val y: Double,
    val routeNodeId: String?,
)

data class VenueRouteNodeResult(
    val nodeId: String,
    val floorId: String,
    val x: Double,
    val y: Double,
    val nodeType: String,
    val refId: String?,
)

data class LocalizationResult(
    val requestId: String,
    val status: String,
    val floorId: String?,
    val x: Double?,
    val y: Double?,
    val confidence: Double,
    val matchedLandmarkDisplayName: String?,
    val matchedLandmarkPoiId: String?,
    val latencyMs: Int,
    val failureStage: String?,
    val suggestedAction: String?,
    val headingMapHeadingDeg: Double?,
    val headingSource: String?,
    val headingConfidence: Double?,
    val traceId: String?,
    val message: String,
)

data class RouteResult(
    val requestId: String,
    val routeId: String,
    val targetPoiId: String,
    val pathNodes: List<String>,
    val nextTurn: String,
    val distanceToNextTurn: Double,
    val distanceToTarget: Double,
    val crossFloorRequired: Boolean,
    val message: String,
)

class ApiClientException(
    override val message: String,
    val code: Int? = null,
    val requestId: String? = null,
) : Exception(message)

class LocalizationApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun health(baseUrl: String): ServiceHealthResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/v1/health")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = parseEnvelope(response)
            val data = body.getJSONObject("data")
            val backendStatus = data.optJSONObject("algorithm_backend_status")
            ServiceHealthResult(
                requestId = body.optString("request_id").ifBlank { data.optString("request_id") },
                status = data.optString("status").ifBlank { "unknown" },
                service = data.optString("service").ifBlank { "pc_backend" },
                version = data.optString("version").ifBlank { "-" },
                serviceMode = data.optString("service_mode").ifBlank { null },
                recognitionMode = data.optString("recognition_mode").ifBlank { null },
                venueId = data.optString("venue_id").ifBlank { null },
                landmarkCount = backendStatus
                    ?.takeIf { it.has("landmark_count") }
                    ?.optInt("landmark_count"),
                algorithmBackendSummary = backendStatus?.compactSummary(),
            )
        }
    }

    suspend fun pairPcBackend(pairingUrl: String): PcBackendPairingConnectionResult = withContext(Dispatchers.IO) {
        val pairing = fetchPairing(pairingUrl.trim())
        if (pairing.type != "visionroute_pc_backend_pairing") {
            throw ApiClientException("二维码不是 VisionRoute PC 后台配对信息：type=${pairing.type.ifBlank { "-" }}")
        }
        if (pairing.baseUrl.isBlank()) {
            throw ApiClientException("配对信息缺少 base_url")
        }
        val candidates = buildList {
            add(pairing.baseUrl)
            pairing.candidateBaseUrls.forEach(::add)
        }.mapNotNull { it.normalizedBaseUrlOrNull() }
            .distinct()
        if (candidates.isEmpty()) {
            throw ApiClientException("配对信息没有可用的 PC 局域网地址")
        }
        val failures = mutableListOf<String>()
        for (candidate in candidates) {
            if (candidate.isLocalOnlyBackendUrl()) {
                failures += "$candidate 跳过：真实手机不可使用本机回环地址"
                continue
            }
            val healthResult = runCatching { health(candidate) }
                .onFailure { failures += "$candidate ${it.message ?: it::class.java.simpleName}" }
                .getOrNull()
            if (healthResult != null) {
                return@withContext PcBackendPairingConnectionResult(
                    pairing = pairing,
                    selectedBaseUrl = candidate,
                    health = healthResult,
                )
            }
        }
        throw ApiClientException(
            "PC 后台连接失败；请确认手机和 PC 同一 Wi‑Fi、PC 后台已启动、Windows 防火墙已放行、手机浏览器可打开 health URL。尝试结果：${failures.joinToString("；")}",
        )
    }

    suspend fun venueMeta(baseUrl: String, venueId: String): VenueMetaResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/v1/venues/$venueId/meta")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = parseEnvelope(response)
            val data = body.getJSONObject("data")
            val supportedFloorsJson = data.optJSONArray("supported_floors")
            val supportedFloors = buildList {
                if (supportedFloorsJson != null) {
                    for (index in 0 until supportedFloorsJson.length()) {
                        add(supportedFloorsJson.optString(index))
                    }
                }
            }
            val poisJson = data.optJSONArray("pois")
            val pois = buildList {
                if (poisJson != null) {
                    for (index in 0 until poisJson.length()) {
                        val item = poisJson.optJSONObject(index) ?: continue
                        val position = item.optJSONObject("position")
                        add(
                            VenuePoiResult(
                                poiId = item.optString("poi_id"),
                                displayName = item.optString("display_name").ifBlank { item.optString("poi_id") },
                                floorId = item.optString("floor_id"),
                                x = position?.optNullableDouble("x") ?: 0.0,
                                y = position?.optNullableDouble("y") ?: 0.0,
                                routeNodeId = item.optString("route_node_id").ifBlank { null },
                            ),
                        )
                    }
                }
            }
            val routeNodesJson = data.optJSONArray("route_nodes")
            val routeNodes = buildList {
                if (routeNodesJson != null) {
                    for (index in 0 until routeNodesJson.length()) {
                        val item = routeNodesJson.optJSONObject(index) ?: continue
                        add(
                            VenueRouteNodeResult(
                                nodeId = item.optString("node_id"),
                                floorId = item.optString("floor_id"),
                                x = item.optNullableDouble("x") ?: 0.0,
                                y = item.optNullableDouble("y") ?: 0.0,
                                nodeType = item.optString("node_type"),
                                refId = item.optString("ref_id").ifBlank { null },
                            ),
                        )
                    }
                }
            }
            VenueMetaResult(
                requestId = body.optString("request_id"),
                venueId = data.getString("venue_id"),
                venueName = data.getString("venue_name"),
                defaultFloorId = data.getString("default_floor_id"),
                supportedFloors = supportedFloors,
                targetPoiCount = data.optInt("target_poi_count", 0),
                packageVersion = data.optString("package_version"),
                pois = pois,
                routeNodes = routeNodes,
            )
        }
    }

    suspend fun locate(
        baseUrl: String,
        venueId: String,
        frame: CapturedFrame,
        targetPoiId: String?,
        debugTarget: String?,
    ): LocalizationResult = withContext(Dispatchers.IO) {
        val requestId = "android_loc_${System.currentTimeMillis()}"
        val captureTimestampMs = frame.captureTimestampMs ?: System.currentTimeMillis()
        val captureId = frame.captureId ?: "cap_$captureTimestampMs"
        val captureMode = frame.captureMode ?: frame.providerId
        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("request_id", requestId)
            .addFormDataPart("capture_id", captureId)
            .addFormDataPart("venue_id", venueId)
            .addFormDataPart("timestamp", isoTimestamp())
            .addFormDataPart("capture_timestamp_ms", captureTimestampMs.toString())
            .addFormDataPart("capture_mode", captureMode)
            .addFormDataPart("device_id", "android_poc")
            .addFormDataPart("current_mode", "indoor_navigation")
            .addFormDataPart("image_format", frame.mimeType.substringAfter('/'))
        frame.candidateFloorId?.takeIf { it.isNotBlank() }?.let {
            multipartBuilder.addFormDataPart("candidate_floor_id", it)
        }
        targetPoiId?.takeIf { it.isNotBlank() }?.let {
            multipartBuilder.addFormDataPart("target_poi_id", it)
        }
        debugTarget?.takeIf { it.isNotBlank() }?.let {
            multipartBuilder.addFormDataPart("debug_target", it)
        }
        frame.width?.let {
            multipartBuilder.addFormDataPart("image_width", it.toString())
        }
        frame.height?.let {
            multipartBuilder.addFormDataPart("image_height", it.toString())
        }
        frame.imuAtCapture?.let { imu ->
            multipartBuilder.addFormDataPart("imu_at_capture", imu.toJsonString())
            multipartBuilder.addFormDataPart("heading_source", imu.source)
        }
        val multipart = multipartBuilder
            .addFormDataPart(
                "image",
                frame.fileName,
                frame.bytes.toRequestBody(frame.mimeType.toMediaType())
            )
            .build()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/v1/localization/visual-locate")
            .header("X-Request-Id", requestId)
            .header("X-App-Version", "android-poc-0.1.0")
            .header("X-Device-Id", "android_poc")
            .post(multipart)
            .build()
        client.newCall(request).execute().use { response ->
            val body = parseEnvelope(response)
            val data = body.getJSONObject("data")
            val position = data.optJSONObject("position")
            val matchedLandmark = data.optJSONObject("matched_landmark")
            val headingHint = data.optJSONObject("heading_hint")
            LocalizationResult(
                requestId = body.optString("request_id").ifBlank { data.optString("request_id") },
                status = data.optString("status").ifBlank { "unknown" },
                floorId = data.optString("floor_id").ifBlank { null },
                x = position?.optNullableDouble("x"),
                y = position?.optNullableDouble("y"),
                confidence = data.optDouble("confidence", 0.0),
                matchedLandmarkDisplayName = matchedLandmark?.optString("display_name")?.ifBlank { null },
                matchedLandmarkPoiId = matchedLandmark?.optString("poi_id")?.ifBlank { null },
                latencyMs = data.optInt("latency_ms", 0),
                failureStage = data.optString("failure_stage").ifBlank { null },
                suggestedAction = data.optString("suggested_action").ifBlank { null },
                headingMapHeadingDeg = headingHint?.optNullableDouble("map_heading_deg"),
                headingSource = headingHint?.optString("source")?.ifBlank { null },
                headingConfidence = headingHint?.optNullableDouble("confidence"),
                traceId = data.optString("trace_id").ifBlank { null },
                message = data.optString("message").ifBlank { body.optString("message") },
            )
        }
    }

    suspend fun requestRoute(
        baseUrl: String,
        venueId: String,
        floorId: String,
        x: Double,
        y: Double,
        targetPoiId: String,
    ): RouteResult = withContext(Dispatchers.IO) {
        val requestId = "android_route_${System.currentTimeMillis()}"
        val payload = JSONObject()
            .put("request_id", requestId)
            .put("venue_id", venueId)
            .put("floor_id", floorId)
            .put("start_position", JSONObject().put("x", x).put("y", y))
            .put("target_poi_id", targetPoiId)
            .put("route_strategy", "fastest")
            .toString()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/v1/navigation/indoor-route")
            .header("X-Request-Id", requestId)
            .header("X-App-Version", "android-poc-0.1.0")
            .header("X-Device-Id", "android_poc")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val body = parseEnvelope(response)
            val data = body.getJSONObject("data")
            val pathNodesJson = data.optJSONArray("path_nodes")
            val pathNodes = buildList {
                if (pathNodesJson != null) {
                    for (index in 0 until pathNodesJson.length()) {
                        add(pathNodesJson.optString(index))
                    }
                }
            }
            RouteResult(
                requestId = body.optString("request_id").ifBlank { requestId },
                routeId = data.optString("route_id").ifBlank { "route_$requestId" },
                targetPoiId = data.optString("target_poi_id").ifBlank { targetPoiId },
                pathNodes = pathNodes,
                nextTurn = data.optString("next_turn").ifBlank { "go_straight" },
                distanceToNextTurn = data.optDouble("distance_to_next_turn", 0.0),
                distanceToTarget = data.getDouble("distance_to_target"),
                crossFloorRequired = data.getBoolean("cross_floor_required"),
                message = body.optString("message"),
            )
        }
    }

    private fun parseEnvelope(response: okhttp3.Response): JSONObject {
        val bodyText = response.body?.string().orEmpty()
        val body = if (bodyText.isBlank()) JSONObject() else JSONObject(bodyText)
        if (!response.isSuccessful) {
            throw buildException(body, "HTTP ${response.code}")
        }
        val code = body.optInt("code", Int.MIN_VALUE)
        if (code != 0) {
            throw buildException(body, "business code $code")
        }
        return body
    }

    private fun fetchPairing(pairingUrl: String): PcBackendPairingResult {
        val request = Request.Builder()
            .url(pairingUrl)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            val body = if (bodyText.isBlank()) JSONObject() else JSONObject(bodyText)
            if (!response.isSuccessful) {
                throw buildException(body, "HTTP ${response.code}")
            }
            return PcBackendPairingResult(
                type = body.optString("type"),
                version = body.optInt("version", 0),
                service = body.optString("service"),
                baseUrl = body.optString("base_url"),
                candidateBaseUrls = body.optJSONArray("candidate_base_urls").toStringList(),
                healthUrl = body.optString("health_url").ifBlank { null },
                visualLocateUrl = body.optString("visual_locate_url").ifBlank { null },
                debugVisualUrl = body.optString("debug_visual_url").ifBlank { null },
                recentRequestsUrl = body.optString("recent_requests_url").ifBlank { null },
                venueId = body.optString("venue_id").ifBlank { null },
                serviceMode = body.optString("service_mode").ifBlank { null },
                recognitionMode = body.optString("recognition_mode").ifBlank { null },
                preferredCaptureMode = body.optString("preferred_capture_mode").ifBlank { null },
            )
        }
    }

    private fun buildException(body: JSONObject, fallbackMessage: String): ApiClientException {
        val code = body.optInt("code", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        val requestId = body.optString("request_id").ifBlank { null }
        val message = body.optString("message").ifBlank { fallbackMessage }
        return ApiClientException(message = message, code = code, requestId = requestId)
    }

    private fun JSONObject.optNullableDouble(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        val value = optDouble(name, Double.NaN)
        return if (value.isNaN()) null else value
    }

    private fun JSONObject.compactSummary(): String {
        val fields = listOfNotNull(
            optString("status").ifBlank { null }?.let { "status=$it" },
            optString("recognition_mode").ifBlank { null }?.let { "recognition=$it" },
            takeIf { has("landmark_count") }?.let { "landmarks=${optInt("landmark_count")}" },
            optString("backend").ifBlank { null }?.let { "backend=$it" },
        )
        return fields.ifEmpty { listOf(toString()) }.joinToString(",")
    }

    private fun org.json.JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun String.normalizedBaseUrlOrNull(): String? {
        val url = trim().trimEnd('/')
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.scheme !in setOf("http", "https")) return null
        val defaultPort = if (parsed.scheme == "https") 443 else 80
        return "${parsed.scheme}://${parsed.host}${if (parsed.port != defaultPort) ":${parsed.port}" else ""}"
    }

    private fun String.isLocalOnlyBackendUrl(): Boolean {
        val host = toHttpUrlOrNull()?.host?.lowercase(java.util.Locale.US) ?: return false
        return host == "localhost" || host == "10.0.2.2" || host.startsWith("127.")
    }

    private fun isoTimestamp(): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
            .format(java.util.Date())
    }
}
