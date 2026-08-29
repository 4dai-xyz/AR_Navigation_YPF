package com.aiglasses.poc.outdoor

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.PoiItem
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch

class AmapOutdoorDiscovery(
    context: Context,
    private val listener: Listener,
) : AMapLocationListener, PoiSearch.OnPoiSearchListener {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var locationClient: AMapLocationClient? = null

    fun requestCurrentLocation() {
        runCatching {
            val client = locationClient ?: AMapLocationClient(appContext).also {
                it.setLocationListener(this)
                locationClient = it
            }
            client.setLocationOption(
                AMapLocationClientOption()
                    .setOnceLocation(true)
                    .setNeedAddress(true)
                    .setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy)
                    .setHttpTimeOut(8000),
            )
            client.startLocation()
        }.onFailure { throwable ->
            dispatch { listener.onOutdoorDiscoveryError("高德定位启动失败：${throwable.message ?: throwable::class.java.simpleName}") }
        }
    }

    fun searchPoi(keyword: String, around: OutdoorPoint?) {
        val trimmedKeyword = keyword.trim()
        if (trimmedKeyword.isBlank()) {
            listener.onOutdoorDiscoveryError("高德地点搜索被拦截：搜索关键字为空")
            return
        }
        runCatching {
            val query = PoiSearch.Query(trimmedKeyword, "", "").apply {
                pageNum = 0
                pageSize = MAX_POI_SEARCH_PAGE_SIZE
                cityLimit = false
                if (around != null) {
                    location = LatLonPoint(around.latitude, around.longitude)
                    isDistanceSort = true
                }
            }
            val search = PoiSearch(appContext, query).apply {
                setOnPoiSearchListener(this@AmapOutdoorDiscovery)
            }
            search.searchPOIAsyn()
        }.onFailure { throwable ->
            dispatch { listener.onOutdoorDiscoveryError("高德地点搜索启动失败：${throwable.message ?: throwable::class.java.simpleName}") }
        }
    }

    fun destroy() {
        locationClient?.stopLocation()
        locationClient?.unRegisterLocationListener(this)
        locationClient?.onDestroy()
        locationClient = null
    }

    override fun onLocationChanged(location: AMapLocation?) {
        if (location == null) {
            dispatch { listener.onOutdoorDiscoveryError("高德定位失败：结果为空") }
            return
        }
        if (location.errorCode != 0) {
            dispatch {
                listener.onOutdoorDiscoveryError(
                    "高德定位失败 code=${location.errorCode} info=${location.errorInfo.orDash()} detail=${location.locationDetail.orDash()}",
                )
            }
            return
        }
        val point = OutdoorPoint(location.latitude, location.longitude)
        if (!point.isLikelyMainlandChina()) {
            dispatch {
                listener.onOutdoorDiscoveryError(
                    "高德定位结果不在中国大陆可导航范围，已忽略：纬度=${point.latitude} 经度=${point.longitude}",
                )
            }
            return
        }
        val summary = "纬度=${point.latitude} 经度=${point.longitude} 精度=${location.accuracy}m 城市=${location.city.orDash()} 地址=${location.address.orDash()}"
        dispatch { listener.onCurrentLocationReady(point, location.city.orEmpty(), summary) }
    }

    override fun onPoiSearched(result: PoiResult?, resultCode: Int) {
        if (resultCode != AMapException.CODE_AMAP_SUCCESS) {
            dispatch { listener.onOutdoorDiscoveryError("高德地点搜索失败 code=$resultCode") }
            return
        }
        val items = result?.pois.orEmpty().mapNotNull { it.toOutdoorPoiOption() }
        dispatch { listener.onPoiSearchResult(items) }
    }

    override fun onPoiItemSearched(item: PoiItem?, resultCode: Int) = Unit

    private fun PoiItem.toOutdoorPoiOption(): OutdoorPoiOption? {
        val point = enter ?: latLonPoint ?: return null
        return OutdoorPoiOption(
            poiId = poiId.orEmpty(),
            title = title.orEmpty(),
            address = snippet.orEmpty(),
            city = cityName.orEmpty(),
            latitude = point.latitude,
            longitude = point.longitude,
        )
    }

    private fun dispatch(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun Any?.orDash(): String = this?.toString()?.takeIf { it.isNotBlank() } ?: "-"

    private fun OutdoorPoint.isLikelyMainlandChina(): Boolean {
        return latitude in 18.0..54.0 && longitude in 73.0..135.0
    }

    private companion object {
        private const val MAX_POI_SEARCH_PAGE_SIZE = 10
    }

    interface Listener {
        fun onCurrentLocationReady(point: OutdoorPoint, city: String, summary: String)
        fun onPoiSearchResult(items: List<OutdoorPoiOption>)
        fun onOutdoorDiscoveryError(summary: String)
    }
}

data class OutdoorPoiOption(
    val poiId: String,
    val title: String,
    val address: String,
    val city: String,
    val latitude: Double,
    val longitude: Double,
    val distanceOverrideMeters: Float? = null,
    val visualType: OutdoorPoiVisualType? = null,
    val directionHint: OutdoorPoiDirectionHint? = null,
    val indoorPoiId: String? = null,
    val indoorFloorId: String? = null,
    val indoorLabel: String? = null,
) {
    fun label(): String {
        val location = "$latitude,$longitude"
        val suffix = listOf(city, address).filter { it.isNotBlank() }.joinToString(" ")
        return if (suffix.isBlank()) "$title | $location" else "$title | $suffix | $location"
    }

    fun point(): OutdoorPoint = OutdoorPoint(latitude, longitude)
}

enum class OutdoorPoiVisualType {
    STORE,
    ENTRANCE,
    OFFICE,
    RESIDENTIAL,
}

enum class OutdoorPoiDirectionHint {
    FORWARD,
    SLIGHT_RIGHT,
    RIGHT,
}
