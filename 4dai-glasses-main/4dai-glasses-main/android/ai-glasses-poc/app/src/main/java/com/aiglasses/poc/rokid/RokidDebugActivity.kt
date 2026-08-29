package com.aiglasses.poc.rokid

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aiglasses.poc.R
import com.aiglasses.poc.databinding.ActivityRokidDebugBinding
import kotlinx.coroutines.launch

class RokidDebugActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRokidDebugBinding
    private lateinit var authManager: RokidAuthManager
    private lateinit var repository: RokidRepository
    private var rokidAiAppInstalled = false
    private var authMessage = ""
    private var appliedBareMetalEndpoint = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRokidDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        authManager = RokidAuthManager(application)
        repository = RokidRepository(this)
        bindActions()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(this, R.string.toast_rokid_android_version_blocked, Toast.LENGTH_LONG).show()
            binding.textRokidStatus.text = getString(R.string.toast_rokid_android_version_blocked)
            setControlsEnabled(false)
            return
        }
        checkRokidAiApp()
        handleAuthorizationReturn()
        renderToken()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.state.collect { renderState(it) }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != AUTH_REQUEST_CODE) return
        authManager.clearAuthorizationPending()
        val result = authManager.parseAuthorizationResult(resultCode, data)
        authMessage = result.message
        Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
        renderToken()
    }

    override fun onDestroy() {
        repository.release()
        super.onDestroy()
    }

    private fun bindActions() {
        binding.buttonBack.setOnClickListener { finish() }
        binding.buttonCheckRokidApp.setOnClickListener { checkRokidAiApp() }
        binding.buttonAuthorizeRokid.setOnClickListener {
            if (!rokidAiAppInstalled) {
                Toast.makeText(this, "请先安装 Rokid AI App", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            authManager.markAuthorizationPending()
            authMessage = "正在打开 Rokid 授权，返回后会自动回到本页"
            renderToken()
            val requestResult = authManager.requestAuthorization(this, AUTH_REQUEST_CODE)
            authMessage = requestResult.message
            if (requestResult.immediateResult != null || !requestResult.launchedAuthorizationPage) {
                authManager.clearAuthorizationPending()
            }
            Toast.makeText(this, requestResult.message, Toast.LENGTH_SHORT).show()
            renderToken()
        }
        binding.buttonInstallRokidAiApp.setOnClickListener {
            authManager.openRokidAiInstallPage(this)
        }
        binding.buttonClearRokidToken.setOnClickListener {
            authManager.clearToken()
            authMessage = "Token 已清除"
            renderToken()
        }
        binding.buttonConnectCustomView.setOnClickListener {
            repository.connectCustomView(authManager.savedToken)
        }
        binding.buttonOpenCustomView.setOnClickListener { repository.openCustomView() }
        binding.buttonCloseCustomView.setOnClickListener { repository.closeCustomView() }
        binding.buttonRokidTakePhoto.setOnClickListener { repository.takePhoto() }
        binding.buttonRokidBareMetalStatus.setOnClickListener {
            repository.probeBareMetalStatus(binding.editRokidBareMetalEndpoint.text?.toString().orEmpty())
        }
        binding.buttonRokidBareMetalDiscover.setOnClickListener {
            repository.discoverBareMetalEndpoint(binding.editRokidBareMetalEndpoint.text?.toString().orEmpty())
        }
        binding.buttonRokidBareMetalCapture.setOnClickListener {
            repository.captureBareMetalFrame(binding.editRokidBareMetalEndpoint.text?.toString().orEmpty())
        }
        binding.buttonRokidBareMetalStartStream.setOnClickListener {
            repository.startBareMetalStream(binding.editRokidBareMetalEndpoint.text?.toString().orEmpty())
        }
        binding.buttonRokidBareMetalStopStream.setOnClickListener { repository.stopBareMetalStream() }
        binding.buttonRokidOfflineStatus.setOnClickListener {
            repository.refreshOfflineCaptureStatus(binding.editRokidBareMetalEndpoint.text?.toString().orEmpty())
        }
        binding.buttonRokidOfflineStart.setOnClickListener {
            repository.startOfflineCapture(binding.editRokidBareMetalEndpoint.text?.toString().orEmpty())
        }
        binding.buttonRokidOfflineStop.setOnClickListener {
            repository.stopOfflineCapture(binding.editRokidBareMetalEndpoint.text?.toString().orEmpty())
        }
        binding.buttonRokidOfflineDownload.setOnClickListener {
            repository.downloadLatestOfflineSession(binding.editRokidBareMetalEndpoint.text?.toString().orEmpty())
        }
        binding.buttonConnectCustomApp.setOnClickListener {
            repository.connectCustomApp(authManager.savedToken)
        }
        binding.buttonQueryCustomApp.setOnClickListener { repository.queryCustomAppInstalled() }
        binding.buttonInstallCustomApp.setOnClickListener {
            repository.installCustomApp(binding.editRokidBareMetalEndpoint.text.toString())
        }
        binding.buttonOpenCustomApp.setOnClickListener { repository.openCustomApp() }
        binding.buttonStopCustomApp.setOnClickListener { repository.stopCustomApp() }
        binding.buttonSendCustomCommand.setOnClickListener { repository.sendCustomCommand() }
        binding.buttonStartRokidRecord.setOnClickListener { repository.startCustomAppRecord() }
        binding.buttonStopRokidRecord.setOnClickListener { repository.stopCustomAppRecord() }
        binding.buttonInjectRokidImu.setOnClickListener { repository.injectDemoImuSample() }
        binding.buttonInjectRokidVoice.setOnClickListener { repository.injectDemoVoiceCommand() }
        binding.buttonSendRokidHud.setOnClickListener { repository.sendDemoHudUpdate() }
    }

    private fun applySystemBarInsets() {
        val initialLeft = binding.root.paddingLeft
        val initialTop = binding.root.paddingTop
        val initialRight = binding.root.paddingRight
        val initialBottom = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                initialLeft + bars.left,
                initialTop + bars.top,
                initialRight + bars.right,
                initialBottom + bars.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun checkRokidAiApp() {
        rokidAiAppInstalled = authManager.isRokidAiAppInstalled(this)
        authMessage = if (rokidAiAppInstalled) "Rokid AI App 已安装" else "未检测到 Rokid AI App"
        renderToken()
        Toast.makeText(this, authMessage, Toast.LENGTH_SHORT).show()
    }

    private fun handleAuthorizationReturn() {
        if (!intent.getBooleanExtra(EXTRA_AUTH_RETURNED, false)) return
        authMessage = if (authManager.savedToken.isNullOrBlank()) {
            "已回到 Rokid 调试页，未检测到新 Token"
        } else {
            "已回到 Rokid 调试页，可继续建立会话"
        }
        Toast.makeText(this, authMessage, Toast.LENGTH_SHORT).show()
    }

    private fun renderToken() {
        val token = authManager.savedToken
        binding.textRokidToken.text = if (token.isNullOrBlank()) {
            "${getString(R.string.text_rokid_token_empty)}\n$authMessage"
        } else {
            "Token：${token.take(8)}…${token.takeLast(6)}\n$authMessage"
        }
    }

    private fun renderState(state: RokidDebugState) {
        binding.textRokidStatus.text = buildList {
            add("连接：CXR-L ${if (state.cxrConnected) "已连接" else "未连接"} · 蓝牙 ${if (state.btConnected) "已连接" else "未连接"}")
            add("会话：${state.mode.label}")
            add("录像：${if (state.customAppRecording) "录制中" else "未录制"}")
            if (state.requiresReauthorization) add("授权：需要刷新")
            add("状态：${state.status}")
        }.joinToString("\n")
        binding.textCustomCommandResponse.text = if (state.lastCommandResponse.isBlank()) {
            "手机指令：暂无"
        } else {
            "手机指令：已通信"
        }
        binding.textRokidRecordInfo.text = state.lastRecordInfo.ifBlank { "暂无眼镜端录像文件信息" }
        binding.textRokidBareMetalHttp.text = state.bareMetalSummary
        binding.textRokidOfflineCapture.text = state.offlineCaptureSummary
        binding.textRokidOfflineSessions.text = state.offlineCaptureSessions
        if (
            state.bareMetalEndpointSuggestion.isNotBlank() &&
            state.bareMetalEndpointSuggestion != appliedBareMetalEndpoint
        ) {
            appliedBareMetalEndpoint = state.bareMetalEndpointSuggestion
            binding.editRokidBareMetalEndpoint.setText(state.bareMetalEndpointSuggestion)
            binding.editRokidBareMetalEndpoint.setSelection(state.bareMetalEndpointSuggestion.length)
        }
        binding.textRokidNavigationDebug.text = listOf(
            "图像：${if (state.lastCaptureSummary.isBlank()) "暂无" else "已收到"}",
            "IMU：${if (state.lastImuSummary.isBlank()) "暂无" else "可用"}",
            "语音：${if (state.lastVoiceCommandSummary.isBlank()) "暂无" else "已收到"}",
            "HUD：${if (state.lastHudSummary.isBlank()) "暂无" else "已下发"}",
        ).joinToString("\n")
        binding.textRokidLogs.text = state.logs.joinToString("\n")
        if (state.lastPhoto != null) {
            binding.imageRokidPhoto.setImageBitmap(state.lastPhoto)
            binding.textRokidPhoto.text = "已收到照片：${state.lastPhoto.width}×${state.lastPhoto.height}"
        } else {
            binding.imageRokidPhoto.setImageDrawable(null)
            binding.textRokidPhoto.setText(R.string.text_rokid_photo_empty)
        }
        if (state.bareMetalPhoto != null) {
            binding.imageRokidBareMetalFrame.setImageBitmap(state.bareMetalPhoto)
            binding.textRokidBareMetalFrame.text = "眼镜画面：${state.bareMetalPhoto.width}×${state.bareMetalPhoto.height}"
        } else {
            binding.imageRokidBareMetalFrame.setImageDrawable(null)
            binding.textRokidBareMetalFrame.text = "暂无眼镜画面。"
        }
        binding.buttonRokidTakePhoto.isEnabled = !state.takingPhoto
        binding.buttonRokidBareMetalStatus.isEnabled = !state.bareMetalLoading && !state.bareMetalStreaming
        binding.buttonRokidBareMetalDiscover.isEnabled = !state.bareMetalLoading && !state.bareMetalStreaming && !state.bareMetalDiscoveryRunning
        binding.buttonRokidBareMetalCapture.isEnabled = !state.bareMetalLoading
        binding.buttonRokidBareMetalStartStream.isEnabled = !state.bareMetalLoading && !state.bareMetalStreaming
        binding.buttonRokidBareMetalStopStream.isEnabled = state.bareMetalStreaming
        binding.buttonRokidOfflineStatus.isEnabled = !state.offlineCaptureDownloading
        binding.buttonRokidOfflineStart.isEnabled = !state.offlineCaptureRecording && !state.offlineCaptureDownloading
        binding.buttonRokidOfflineStop.isEnabled = state.offlineCaptureRecording && !state.offlineCaptureDownloading
        binding.buttonRokidOfflineDownload.isEnabled = !state.offlineCaptureRecording && !state.offlineCaptureDownloading
        binding.buttonInstallCustomApp.isEnabled = !state.installing
        binding.buttonStartRokidRecord.isEnabled = !state.customAppRecording
        binding.buttonStopRokidRecord.isEnabled = state.customAppRecording
    }

    private fun setControlsEnabled(enabled: Boolean) {
        listOf(
            binding.buttonCheckRokidApp,
            binding.buttonAuthorizeRokid,
            binding.buttonInstallRokidAiApp,
            binding.buttonClearRokidToken,
            binding.buttonConnectCustomView,
            binding.buttonOpenCustomView,
            binding.buttonCloseCustomView,
            binding.buttonRokidTakePhoto,
            binding.buttonRokidBareMetalDiscover,
            binding.buttonRokidBareMetalStatus,
            binding.buttonRokidBareMetalCapture,
            binding.buttonRokidBareMetalStartStream,
            binding.buttonRokidBareMetalStopStream,
            binding.buttonRokidOfflineStatus,
            binding.buttonRokidOfflineStart,
            binding.buttonRokidOfflineStop,
            binding.buttonRokidOfflineDownload,
            binding.buttonConnectCustomApp,
            binding.buttonQueryCustomApp,
            binding.buttonInstallCustomApp,
            binding.buttonOpenCustomApp,
            binding.buttonStopCustomApp,
            binding.buttonSendCustomCommand,
            binding.buttonStartRokidRecord,
            binding.buttonStopRokidRecord,
            binding.buttonInjectRokidImu,
            binding.buttonInjectRokidVoice,
            binding.buttonSendRokidHud,
        ).forEach { button ->
            button.isEnabled = enabled
            button.visibility = if (enabled) View.VISIBLE else View.GONE
        }
    }

    companion object {
        private const val AUTH_REQUEST_CODE = 3001
        private const val EXTRA_AUTH_RETURNED = "com.aiglasses.poc.rokid.AUTH_RETURNED"

        fun createAuthorizationReturnIntent(context: Context): Intent {
            return Intent(context, RokidDebugActivity::class.java)
                .putExtra(EXTRA_AUTH_RETURNED, true)
        }
    }
}
