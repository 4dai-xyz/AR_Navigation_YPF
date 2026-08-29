package com.aiglasses.poc.outdoor

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class ExternalLocationPayload(
    val sourceLabel: String,
    val title: String?,
    val point: OutdoorPoint?,
    val keyword: String,
)

object ExternalLocationIntentParser {
    fun parse(
        action: String?,
        dataString: String?,
        mimeType: String?,
        sharedText: String?,
    ): ExternalLocationPayload? {
        dataString?.takeIf { it.isNotBlank() }?.let { data ->
            parseLink(data, sharedText)?.let { return it }
        }
        sharedText?.takeIf { it.isNotBlank() }?.let { text ->
            parseSharedText(text)?.let { return it }
        }
        if (action == null && mimeType == null) {
            return null
        }
        return null
    }

    private fun parseSharedText(text: String): ExternalLocationPayload? {
        val url = URL_PATTERN.find(text)?.value
        if (url != null) {
            parseLink(url, text)?.let { return it }
        }
        val title = cleanupSharedText(text)
        val point = findLatLng(text)
        return when {
            point != null -> ExternalLocationPayload(
                sourceLabel = "外部分享",
                title = title.ifBlank { "外部位置" },
                point = point,
                keyword = title.ifBlank { "外部位置" },
            )
            title.isNotBlank() -> ExternalLocationPayload(
                sourceLabel = "外部分享",
                title = title,
                point = null,
                keyword = title,
            )
            else -> null
        }
    }

    private fun parseLink(link: String, fallbackText: String?): ExternalLocationPayload? {
        val decodedLink = decode(link)
        val lower = decodedLink.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("geo:") -> parseGeoUri(decodedLink, fallbackText)
            lower.startsWith("androidamap://") ||
                lower.startsWith("amapuri://") ||
                lower.contains("amap.com") -> parseAmapUri(decodedLink, fallbackText)
            lower.startsWith("mqqapi://") ||
                lower.startsWith("qqmap://") ||
                lower.contains("map.qq.com") -> parseTencentUri(decodedLink, fallbackText)
            else -> parseGenericMapLink(decodedLink, fallbackText)
        }
    }

    private fun parseGeoUri(uri: String, fallbackText: String?): ExternalLocationPayload? {
        val body = uri.removePrefix("geo:").substringBefore("#")
        val path = body.substringBefore("?")
        val query = body.substringAfter("?", missingDelimiterValue = "")
        val params = parseQuery(query)
        val q = params["q"].orEmpty()
        val point = findLatLng(q) ?: parseCoordinatePair(path, CoordinateOrder.LAT_LNG)
        val title = extractParenthesizedLabel(q)
            ?: cleanupKeyword(q)
            ?: cleanupSharedText(fallbackText.orEmpty()).ifBlank { null }
        val keyword = title ?: point?.let { "外部位置" }.orEmpty()
        return if (point != null || keyword.isNotBlank()) {
            ExternalLocationPayload(
                sourceLabel = "geo 位置",
                title = title,
                point = point,
                keyword = keyword,
            )
        } else {
            null
        }
    }

    private fun parseAmapUri(uri: String, fallbackText: String?): ExternalLocationPayload? {
        val params = parseQuery(uri.substringAfter("?", missingDelimiterValue = ""))
        val point = pointFromLatLonParams(params)
            ?: params["position"]?.let { parseCoordinatePair(it, CoordinateOrder.LNG_LAT) }
            ?: params["to"]?.let { parseAmapToPoint(it) }
            ?: findLatLng(uri)
        val title = firstNonBlank(
            params["poiname"],
            params["dname"],
            params["name"],
            params["keyword"],
            params["q"],
            params["to"]?.split(",")?.drop(2)?.joinToString(","),
            cleanupSharedText(fallbackText.orEmpty()),
        )
        val keyword = title.ifBlank { point?.let { "外部位置" }.orEmpty() }
        return if (point != null || keyword.isNotBlank()) {
            ExternalLocationPayload(
                sourceLabel = "高德地图链接",
                title = title.ifBlank { null },
                point = point,
                keyword = keyword,
            )
        } else {
            null
        }
    }

    private fun parseTencentUri(uri: String, fallbackText: String?): ExternalLocationPayload? {
        val params = parseQuery(uri.substringAfter("?", missingDelimiterValue = ""))
        val marker = params["marker"].orEmpty()
        val point = parseTencentMarkerPoint(marker)
            ?: params["coord"]?.let { parseCoordinatePair(it, CoordinateOrder.LAT_LNG) }
            ?: params["tocoord"]?.let { parseCoordinatePair(it, CoordinateOrder.LAT_LNG) }
            ?: params["center"]?.let { parseCoordinatePair(it, CoordinateOrder.LAT_LNG) }
            ?: pointFromLatLonParams(params)
            ?: findLatLng(uri)
        val title = firstNonBlank(
            parseTencentMarkerTitle(marker),
            params["title"],
            params["name"],
            params["to"],
            params["word"],
            params["keyword"],
            cleanupSharedText(fallbackText.orEmpty()),
        )
        val keyword = title.ifBlank { point?.let { "外部位置" }.orEmpty() }
        return if (point != null || keyword.isNotBlank()) {
            ExternalLocationPayload(
                sourceLabel = "腾讯地图链接",
                title = title.ifBlank { null },
                point = point,
                keyword = keyword,
            )
        } else {
            null
        }
    }

    private fun parseGenericMapLink(uri: String, fallbackText: String?): ExternalLocationPayload? {
        val params = parseQuery(uri.substringAfter("?", missingDelimiterValue = ""))
        val point = pointFromLatLonParams(params) ?: findLatLng(uri)
        val title = firstNonBlank(
            params["name"],
            params["title"],
            params["q"],
            params["query"],
            params["keyword"],
            cleanupSharedText(fallbackText.orEmpty()),
        )
        val keyword = title.ifBlank { point?.let { "外部位置" }.orEmpty() }
        return if (point != null || keyword.isNotBlank()) {
            ExternalLocationPayload(
                sourceLabel = "外部地图链接",
                title = title.ifBlank { null },
                point = point,
                keyword = keyword,
            )
        } else {
            null
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) {
            return emptyMap()
        }
        return query
            .substringBefore("#")
            .split("&")
            .mapNotNull { pair ->
                val key = pair.substringBefore("=", missingDelimiterValue = "").trim()
                if (key.isBlank()) {
                    null
                } else {
                    val value = pair.substringAfter("=", missingDelimiterValue = "")
                    decode(key).lowercase(Locale.ROOT) to decode(value)
                }
            }
            .toMap()
    }

    private fun pointFromLatLonParams(params: Map<String, String>): OutdoorPoint? {
        val latitude = firstNonBlank(
            params["lat"],
            params["latitude"],
            params["dlat"],
            params["to_lat"],
        ).toDoubleOrNull()
        val longitude = firstNonBlank(
            params["lon"],
            params["lng"],
            params["longitude"],
            params["dlon"],
            params["to_lng"],
        ).toDoubleOrNull()
        return toOutdoorPoint(latitude, longitude)
    }

    private fun parseAmapToPoint(value: String): OutdoorPoint? {
        val parts = value.split(",")
        if (parts.size < 2) {
            return null
        }
        return toOutdoorPoint(
            latitude = parts[1].trim().toDoubleOrNull(),
            longitude = parts[0].trim().toDoubleOrNull(),
        )
    }

    private fun parseTencentMarkerPoint(marker: String): OutdoorPoint? {
        val coord = marker.substringAfter("coord:", missingDelimiterValue = "")
            .substringBefore(";")
        return parseCoordinatePair(coord, CoordinateOrder.LAT_LNG)
    }

    private fun parseTencentMarkerTitle(marker: String): String {
        return marker.substringAfter("title:", missingDelimiterValue = "")
            .substringBefore(";")
            .trim()
    }

    private fun findLatLng(text: String): OutdoorPoint? {
        return COORDINATE_PATTERN.findAll(text)
            .mapNotNull { match ->
                val first = match.groupValues[1].toDoubleOrNull()
                val second = match.groupValues[2].toDoubleOrNull()
                toOutdoorPoint(first, second) ?: toOutdoorPoint(second, first)
            }
            .firstOrNull()
    }

    private fun parseCoordinatePair(value: String, order: CoordinateOrder): OutdoorPoint? {
        val match = COORDINATE_PATTERN.find(value) ?: return null
        val first = match.groupValues[1].toDoubleOrNull()
        val second = match.groupValues[2].toDoubleOrNull()
        return when (order) {
            CoordinateOrder.LAT_LNG -> toOutdoorPoint(first, second)
            CoordinateOrder.LNG_LAT -> toOutdoorPoint(second, first)
        }
    }

    private fun toOutdoorPoint(latitude: Double?, longitude: Double?): OutdoorPoint? {
        if (latitude == null || longitude == null) {
            return null
        }
        return OutdoorPoint(latitude, longitude).takeIf {
            latitude in -90.0..90.0 && longitude in -180.0..180.0
        }
    }

    private fun extractParenthesizedLabel(value: String): String? {
        return value.substringAfter("(", missingDelimiterValue = "")
            .substringBefore(")", missingDelimiterValue = "")
            .trim()
            .takeIf { it.isNotBlank() && findLatLng(it) == null }
    }

    private fun cleanupKeyword(value: String): String? {
        val cleaned = value
            .replace(COORDINATE_PATTERN, "")
            .replace("(", " ")
            .replace(")", " ")
            .trim()
        return cleaned.takeIf { it.isNotBlank() }
    }

    private fun cleanupSharedText(value: String): String {
        return value
            .replace(URL_PATTERN, " ")
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .take(80)
            .trim()
    }

    private fun firstNonBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() }.orEmpty().trim()
    }

    private fun decode(value: String): String {
        return runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrDefault(value)
    }

    private enum class CoordinateOrder {
        LAT_LNG,
        LNG_LAT,
    }

    private val URL_PATTERN = Regex("""https?://\S+|geo:\S+|androidamap://\S+|amapuri://\S+|mqqapi://\S+|qqmap://\S+""")
    private val COORDINATE_PATTERN = Regex("""(-?\d{1,3}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)""")
}
