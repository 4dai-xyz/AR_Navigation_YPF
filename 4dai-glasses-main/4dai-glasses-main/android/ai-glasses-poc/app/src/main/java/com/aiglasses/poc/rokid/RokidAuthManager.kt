package com.aiglasses.poc.rokid

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import com.rokid.sprite.aiapp.externalapp.auth.GlassPermission

data class RokidAuthParseResult(
    val success: Boolean,
    val token: String?,
    val message: String,
)

data class RokidAuthRequestResult(
    val launchedAuthorizationPage: Boolean,
    val immediateResult: RokidAuthParseResult?,
    val message: String,
)

class RokidAuthManager(
    private val app: Application,
) {
    private val prefs = app.getSharedPreferences("rokid_auth", Context.MODE_PRIVATE)

    val savedToken: String?
        get() = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun markAuthorizationPending() {
        prefs.edit().putLong(KEY_AUTH_PENDING_AT, System.currentTimeMillis()).apply()
    }

    fun clearAuthorizationPending() {
        prefs.edit().remove(KEY_AUTH_PENDING_AT).apply()
    }

    fun consumeAuthorizationPending(maxAgeMs: Long = AUTH_PENDING_MAX_AGE_MS): Boolean {
        val pendingAt = prefs.getLong(KEY_AUTH_PENDING_AT, 0L)
        if (pendingAt <= 0L) return false
        clearAuthorizationPending()
        return System.currentTimeMillis() - pendingAt <= maxAgeMs
    }

    fun isRokidAiAppInstalled(activity: Activity): Boolean {
        return AuthorizationHelper.isRequiredRokidAppInstalled(activity)
    }

    fun requestAuthorization(activity: Activity, requestCode: Int): RokidAuthRequestResult {
        return runCatching {
            val authorizationResult = AuthorizationHelper.requestAuthorization(
                activity,
                arrayOf(GlassPermission.CAMERA, GlassPermission.MEDIA, GlassPermission.MICROPHONE),
                requestCode,
            )
            if (authorizationResult != null) {
                val parsedResult = parseAuthorizationResult(
                    authorizationResult.first ?: Activity.RESULT_CANCELED,
                    authorizationResult.second,
                )
                RokidAuthRequestResult(
                    launchedAuthorizationPage = false,
                    immediateResult = parsedResult,
                    message = parsedResult.message,
                )
            } else {
                RokidAuthRequestResult(
                    launchedAuthorizationPage = true,
                    immediateResult = null,
                    message = "已请求打开 Rokid 授权页",
                )
            }
        }.getOrElse { error ->
            RokidAuthRequestResult(
                launchedAuthorizationPage = false,
                immediateResult = null,
                message = "Rokid 授权启动失败：${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    fun parseAuthorizationResult(resultCode: Int, data: Intent?): RokidAuthParseResult {
        val result = AuthorizationHelper.parseAuthorizationResult(resultCode, data)
            ?: return RokidAuthParseResult(false, null, "Rokid 授权失败：未返回授权结果")
        return when (result) {
            is AuthResult.AuthSuccess -> {
                val token = result.component1()
                prefs.edit().putString(KEY_TOKEN, token).apply()
                RokidAuthParseResult(true, token, "Rokid 授权成功")
            }
            is AuthResult.AuthFail -> {
                prefs.edit().remove(KEY_TOKEN).apply()
                RokidAuthParseResult(false, null, "Rokid 授权失败")
            }
            else -> {
                prefs.edit().remove(KEY_TOKEN).apply()
                RokidAuthParseResult(false, null, "Rokid 授权已取消")
            }
        }
    }

    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    fun openRokidAiInstallPage(context: Context) {
        val uri = Uri.parse("market://details?id=com.rokid.sprite.aiapp")
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_AUTH_PENDING_AT = "authorization_pending_at"
        private const val AUTH_PENDING_MAX_AGE_MS = 2 * 60 * 1000L
    }
}
