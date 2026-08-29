package com.rokid.cxrswithcxrl.activities.main

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import com.rokid.cxrswithcxrl.R
import com.rokid.cxrswithcxrl.receiver.KeyReceiver
import com.rokid.cxrswithcxrl.receiver.KeyType
import com.rokid.cxrswithcxrl.ui.theme.CXRSWithCXRLTheme

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel
    private var wakeLock: PowerManager.WakeLock? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepGlassesAwake()
        startBridgeForegroundService()
        enableEdgeToEdge()
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        viewModel.init(applicationContext)
        viewModel.onExitRequested = { runOnUiThread { exitGlassesApp() } }
        requestRuntimePermissionsIfNeeded()
        setContent {
            CXRSWithCXRLTheme {
                MainScreen(
                    viewModel = viewModel
                )
            }
        }
        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitGlassesApp()
            }
        })
        registerReceiver(viewModel.keyReceiver, IntentFilter().apply {
            KeyType.entries.forEach {
                addAction(it.action)
            }
        })
    }

    override fun onDestroy() {
        viewModel.onExitRequested = null
        unregisterReceiver(viewModel.keyReceiver)
        viewModel.release()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val missingPermissions = listOf(
            Manifest.permission.CAMERA,
        ).filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isEmpty()) return
        requestPermissions(missingPermissions.toTypedArray(), REQUEST_RUNTIME_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            exitGlassesApp()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun exitGlassesApp() {
        stopService(Intent(this, RokidBridgeForegroundService::class.java))
        viewModel.exitApp(this)
    }

    private fun startBridgeForegroundService() {
        val intent = Intent(this, RokidBridgeForegroundService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun keepGlassesAwake() {
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
        )
        window.decorView.keepScreenOn = true
        wakeLock = (getSystemService(POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "VisionRoute:RokidBareMetal",
            )
            ?.apply {
                setReferenceCounted(false)
                runCatching { acquire(WAKE_LOCK_TIMEOUT_MS) }
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

}

private const val REQUEST_RUNTIME_PERMISSIONS = 7001
private const val WAKE_LOCK_TIMEOUT_MS = 2L * 60L * 60L * 1000L
private const val CONFERENCE_HUD_MAP_ASPECT_RATIO = 940f / 1230f
private const val CONFERENCE_HUD_MAP_FOLLOW_ZOOM = 2.35f

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val hudState by viewModel.hudUiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(color = Color.Black),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GreenText(text = "VisionRoute HUD", fontSizeSp = 14)
        Spacer(modifier = Modifier.padding(vertical = 6.dp))
        HudPanel(hudState)
    }
}

@Composable
fun HudPanel(state: RokidHudUiState) {
    val isAlert = state.alertText.isNotBlank()
    val primaryColor = if (isAlert) Color(0xFFFFD166) else Color(0xFF00FF66)
    val remainingLine = listOfNotNull(
        state.remainingDistance.takeIf { it.isNotBlank() }?.let { "剩余 $it" },
        state.remainingDuration.takeIf { it.isNotBlank() }?.let { "预计 $it" },
    ).joinToString(" · ")
    val nextLine = state.nextDistance.takeIf { it.isNotBlank() }?.let { "下一动作 $it" }.orEmpty()

    GreenText(text = state.directionArrow, fontSizeSp = 72, textColor = primaryColor)
    GreenText(text = state.nextAction, fontSizeSp = 26, textColor = primaryColor)
    Spacer(modifier = Modifier.padding(vertical = 3.dp))
    GreenText(text = "目标：${state.targetName}", fontSizeSp = 16)
    GreenText(text = remainingLine.ifBlank { state.statusText }, fontSizeSp = 14)
    GreenText(
        text = listOf(
            state.currentLocationName.takeIf { it.isNotBlank() }?.let { "当前位置：$it" },
            nextLine,
        ).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
        fontSizeSp = 14,
    )
    MiniMapHud(state)
    if (isAlert) {
        GreenText(text = "提示：${state.alertText}", fontSizeSp = 14, textColor = Color(0xFFFFD166))
    } else {
        GreenText(text = state.statusText, fontSizeSp = 12)
    }
}

@Composable
fun MiniMapHud(state: RokidHudUiState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(196.dp)
            .padding(horizontal = 18.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .width(260.dp)
                .height(184.dp)
                .clipToBounds()
                .background(Color(0xFF111111)),
        ) {
            val currentPoint = state.miniMapCurrent
            val zoom = if (currentPoint == null) 1f else CONFERENCE_HUD_MAP_FOLLOW_ZOOM
            val viewportWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val viewportHeight = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            val mapWidth = viewportHeight * CONFERENCE_HUD_MAP_ASPECT_RATIO
            val mapHeight = viewportHeight
            val translationX = currentPoint?.let {
                followMapTranslation(viewportWidth, mapWidth, it.x, zoom)
            } ?: 0f
            val translationY = currentPoint?.let {
                followMapTranslation(viewportHeight, mapHeight, it.y, zoom)
            } ?: 0f

            Image(
                painter = painterResource(id = R.drawable.conference_hud_map),
                contentDescription = "会场黑白底图",
                modifier = Modifier
                    .height(184.dp)
                    .aspectRatio(CONFERENCE_HUD_MAP_ASPECT_RATIO)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = zoom
                        scaleY = zoom
                        this.translationX = translationX
                        this.translationY = translationY
                    },
                contentScale = ContentScale.FillBounds,
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    color = Color(0xDD00FF66),
                    style = Stroke(width = 1.8f),
                )

                fun mapPoint(point: RokidHudMapPoint): Offset {
                    return Offset(
                        x = (point.x / 1000f) * mapWidth * zoom + translationX,
                        y = (point.y / 1000f) * mapHeight * zoom + translationY,
                    )
                }

                state.miniMapTarget?.let { point ->
                    val center = mapPoint(point)
                    drawCircle(
                        color = Color(0xFFE0E0E0),
                        radius = 8f,
                        center = center,
                        style = Stroke(width = 2f),
                    )
                    drawLine(Color(0xFFE0E0E0), Offset(center.x - 5f, center.y), Offset(center.x + 5f, center.y), strokeWidth = 1.5f)
                    drawLine(Color(0xFFE0E0E0), Offset(center.x, center.y - 5f), Offset(center.x, center.y + 5f), strokeWidth = 1.5f)
                }
                state.miniMapCurrent?.let { point ->
                    val center = mapPoint(point)
                    drawCircle(
                        color = Color.White,
                        radius = 9.5f,
                        center = center,
                        style = Stroke(width = 2.5f),
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 2.2f,
                        center = center,
                    )
                }
            }
        }
    }
}

private fun followMapTranslation(
    viewportSizePx: Float,
    mapSizePx: Float,
    normalizedCoordinate: Float,
    zoom: Float,
): Float {
    val scaledMapSize = mapSizePx * zoom
    if (scaledMapSize <= viewportSizePx) {
        return (viewportSizePx - scaledMapSize) / 2f
    }
    val mapCoordinatePx = (normalizedCoordinate.coerceIn(0f, 1000f) / 1000f) * mapSizePx
    val centered = viewportSizePx / 2f - mapCoordinatePx * zoom
    val minTranslation = viewportSizePx - scaledMapSize
    return centered.coerceIn(minTranslation, 0f)
}

@Composable
fun GreenText(
    text: String,
    fontSizeSp: Int = 14,
    textColor: Color = Color(0xFF00AF00),
) {

    Text(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        text = text,
        color = textColor,
        fontSize = fontSizeSp.sp,
        textAlign = TextAlign.Center,
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    CXRSWithCXRLTheme {
        MainScreen(viewModel = MainViewModel())
    }
}
