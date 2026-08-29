package com.aiglasses.poc.outdoor

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.amap.api.navi.AMapNavi
import com.amap.api.services.core.ServiceSettings

data class AmapInitResult(
    val keyConfigured: Boolean,
    val sdkConfigured: Boolean,
    val message: String,
)

object AmapOutdoorBridge {
    private const val API_KEY_META_DATA = "com.amap.api.v2.apikey"

    fun initialize(context: Context): AmapInitResult {
        val appContext = context.applicationContext
        val apiKey = readApiKey(appContext)
        runCatching {
            MapsInitializer.updatePrivacyShow(appContext, true, true)
            MapsInitializer.updatePrivacyAgree(appContext, true)
            AMapLocationClient.updatePrivacyShow(appContext, true, true)
            AMapLocationClient.updatePrivacyAgree(appContext, true)
            ServiceSettings.updatePrivacyShow(appContext, true, true)
            ServiceSettings.updatePrivacyAgree(appContext, true)
        }.onFailure { throwable ->
            return AmapInitResult(
                keyConfigured = apiKey.isNotBlank(),
                sdkConfigured = false,
                message = "高德隐私合规初始化失败：${throwable.message ?: throwable::class.java.simpleName}",
            )
        }
        if (apiKey.isBlank()) {
            return AmapInitResult(
                keyConfigured = false,
                sdkConfigured = false,
                message = "缺少高德 Key；请在 local.properties 设置 amap.apiKey",
            )
        }

        return runCatching {
            MapsInitializer.setApiKey(apiKey)
            AMapLocationClient.setApiKey(apiKey)
            AMapNavi.setApiKey(appContext, apiKey)
            ServiceSettings.getInstance().setApiKey(apiKey)
            AmapInitResult(
                keyConfigured = true,
                sdkConfigured = true,
                message = "高德 SDK Key 与隐私合规已初始化",
            )
        }.getOrElse { throwable ->
            AmapInitResult(
                keyConfigured = true,
                sdkConfigured = false,
                message = "高德 SDK 初始化失败：${throwable.message ?: throwable::class.java.simpleName}",
            )
        }
    }

    private fun readApiKey(context: Context): String {
        val applicationInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        }
        return applicationInfo.metaData?.getString(API_KEY_META_DATA)?.trim().orEmpty()
    }
}
