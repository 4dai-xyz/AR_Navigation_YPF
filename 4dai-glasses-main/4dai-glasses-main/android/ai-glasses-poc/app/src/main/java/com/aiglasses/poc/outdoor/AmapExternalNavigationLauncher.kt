package com.aiglasses.poc.outdoor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.aiglasses.poc.R
import java.util.Locale

class AmapExternalNavigationLauncher(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val sourceApplication = appContext.getString(R.string.app_name)

    fun openNavigation(request: AmapExternalNavigationRequest): AmapExternalNavigationResult {
        val installed = isAmapInstalled()
        if (!installed) {
            return AmapExternalNavigationResult(
                success = false,
                installed = false,
                launchedUri = null,
                message = "未安装高德地图 App，无法调起外部导航",
            )
        }

        val launchErrors = mutableListOf<String>()
        for (uri in request.candidateUris(sourceApplication)) {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(AMAP_PACKAGE_NAME)
                addCategory(Intent.CATEGORY_DEFAULT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                appContext.startActivity(intent)
            }.onSuccess {
                return AmapExternalNavigationResult(
                    success = true,
                    installed = true,
                    launchedUri = uri.toString(),
                    message = "已调起高德地图 App",
                )
            }.onFailure { throwable ->
                launchErrors += "${throwable::class.java.simpleName}:${throwable.message.orEmpty()}"
                if (throwable !is ActivityNotFoundException && throwable !is SecurityException) {
                    return AmapExternalNavigationResult(
                        success = false,
                        installed = true,
                        launchedUri = uri.toString(),
                        message = "调起高德地图 App 失败：${throwable.message ?: throwable::class.java.simpleName}",
                    )
                }
            }
        }

        return AmapExternalNavigationResult(
            success = false,
            installed = true,
            launchedUri = null,
            message = "高德地图 App 已安装，但外部导航 URI 无法处理：${launchErrors.joinToString(";")}",
        )
    }

    fun isAmapInstalled(): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(AMAP_PACKAGE_NAME, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(AMAP_PACKAGE_NAME, 0)
            }
        }.isSuccess
    }

    private fun AmapExternalNavigationRequest.candidateUris(sourceApplication: String): List<Uri> {
        val routeType = when (travelMode) {
            OutdoorTravelMode.DRIVE -> AMAP_ROUTE_TYPE_DRIVE
            OutdoorTravelMode.EBIKE -> AMAP_ROUTE_TYPE_RIDE
            OutdoorTravelMode.RIDE -> AMAP_ROUTE_TYPE_RIDE
            OutdoorTravelMode.WALK -> AMAP_ROUTE_TYPE_WALK
        }
        val planUri = Uri.Builder()
            .scheme("amapuri")
            .authority("route")
            .appendPath("plan")
            .appendQueryParameter("sourceApplication", sourceApplication)
            .appendQueryParameter("dname", entranceName)
            .appendQueryParameter("dlat", latitude.formatCoordinate())
            .appendQueryParameter("dlon", longitude.formatCoordinate())
            .appendQueryParameter("dev", AMAP_COORDINATE_GCJ02)
            .appendQueryParameter("t", routeType)
            .apply {
                when (travelMode) {
                    OutdoorTravelMode.RIDE -> appendQueryParameter("rideType", "bike")
                    OutdoorTravelMode.EBIKE -> appendQueryParameter("rideType", "elebike")
                    else -> Unit
                }
            }
            .build()

        val legacyRouteUri = Uri.Builder()
            .scheme("androidamap")
            .authority("route")
            .appendQueryParameter("sourceApplication", sourceApplication)
            .appendQueryParameter("dname", entranceName)
            .appendQueryParameter("dlat", latitude.formatCoordinate())
            .appendQueryParameter("dlon", longitude.formatCoordinate())
            .appendQueryParameter("dev", AMAP_COORDINATE_GCJ02)
            .appendQueryParameter("m", "0")
            .appendQueryParameter("t", routeType)
            .build()

        val naviUri = Uri.Builder()
            .scheme("androidamap")
            .authority("navi")
            .appendQueryParameter("sourceApplication", sourceApplication)
            .appendQueryParameter("poiname", entranceName)
            .appendQueryParameter("lat", latitude.formatCoordinate())
            .appendQueryParameter("lon", longitude.formatCoordinate())
            .appendQueryParameter("dev", AMAP_COORDINATE_GCJ02)
            .appendQueryParameter("style", AMAP_NAVI_STYLE_DEFAULT)
            .build()

        return listOf(planUri, legacyRouteUri, naviUri)
    }

    private fun Double.formatCoordinate(): String {
        return String.format(Locale.US, "%.6f", this)
    }

    companion object {
        const val AMAP_PACKAGE_NAME = "com.autonavi.minimap"
        private const val AMAP_COORDINATE_GCJ02 = "0"
        private const val AMAP_ROUTE_TYPE_DRIVE = "0"
        private const val AMAP_ROUTE_TYPE_WALK = "2"
        private const val AMAP_ROUTE_TYPE_RIDE = "3"
        private const val AMAP_NAVI_STYLE_DEFAULT = "2"
    }
}

data class AmapExternalNavigationRequest(
    val entranceName: String,
    val latitude: Double,
    val longitude: Double,
    val travelMode: OutdoorTravelMode,
)

data class AmapExternalNavigationResult(
    val success: Boolean,
    val installed: Boolean,
    val launchedUri: String?,
    val message: String,
)
