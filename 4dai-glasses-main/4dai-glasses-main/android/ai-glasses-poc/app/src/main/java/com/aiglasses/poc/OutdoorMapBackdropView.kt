package com.aiglasses.poc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class OutdoorMapBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private var mode: Mode = Mode.SEARCH_HOME
    private var targetTitle: String? = null
    private var entranceLabel: String? = null

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F6F5F1")
        style = Paint.Style.FILL
    }
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FAFBFD")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val roadEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6E8EB")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8EBEF")
        style = Paint.Style.FILL
    }
    private val parkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DDEBDD")
        style = Paint.Style.FILL
    }
    private val routeShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFDFD")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2F80ED")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val blueDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2F80ED")
        style = Paint.Style.FILL
    }
    private val blueDotHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8F2FF")
        style = Paint.Style.FILL
    }
    private val orangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F58A34")
        style = Paint.Style.FILL
    }
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5B6470")
        textSize = 14f * resources.displayMetrics.scaledDensity
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#111827")
        textSize = 14f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FDFDFB")
        style = Paint.Style.FILL
    }
    private val bubbleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#15000000")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }

    fun render(
        mode: Mode,
        targetTitle: String?,
        entranceLabel: String?,
    ) {
        this.mode = mode
        this.targetTitle = targetTitle
        this.entranceLabel = entranceLabel
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        drawParks(canvas)
        drawRoadNetwork(canvas)
        drawBlocks(canvas)
        drawMapLabels(canvas)
        when (mode) {
            Mode.SEARCH_HOME -> drawSearchCenter(canvas)
            Mode.SEARCH_RESULTS -> {
                drawSearchCenter(canvas)
                drawHintPulse(canvas)
            }
            Mode.SELECTION,
            Mode.ROUTE_READY,
            Mode.NAVIGATING,
            Mode.HANDOFF,
            -> {
                drawRoute(canvas)
                drawCurrentMarker(canvas)
                drawTargetMarker(canvas)
                drawTargetBubble(canvas)
            }
        }
    }

    private fun drawParks(canvas: Canvas) {
        canvas.drawRoundRect(rect(0.58f, 0.12f, 0.92f, 0.37f), dp(28f), dp(28f), parkPaint)
        canvas.drawRoundRect(rect(0.12f, 0.62f, 0.38f, 0.86f), dp(22f), dp(22f), parkPaint)
        canvas.drawRoundRect(rect(0.62f, 0.70f, 0.90f, 0.92f), dp(18f), dp(18f), parkPaint)
    }

    private fun drawRoadNetwork(canvas: Canvas) {
        roadEdgePaint.strokeWidth = dp(28f)
        roadPaint.strokeWidth = dp(24f)
        drawRoad(canvas, 0.06f, 0.24f, 0.94f, 0.20f)
        drawRoad(canvas, 0.11f, 0.52f, 0.88f, 0.48f)
        drawRoad(canvas, 0.08f, 0.82f, 0.88f, 0.76f)
        drawRoad(canvas, 0.20f, 0.08f, 0.24f, 0.94f)
        drawRoad(canvas, 0.47f, 0.04f, 0.50f, 0.90f)
        drawRoad(canvas, 0.76f, 0.12f, 0.80f, 0.88f)
        drawRoad(canvas, 0.30f, 0.24f, 0.62f, 0.52f)
        drawRoad(canvas, 0.52f, 0.55f, 0.84f, 0.83f)
    }

    private fun drawRoad(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        canvas.drawLine(px(x1), py(y1), px(x2), py(y2), roadEdgePaint)
        canvas.drawLine(px(x1), py(y1), px(x2), py(y2), roadPaint)
    }

    private fun drawBlocks(canvas: Canvas) {
        val blocks = listOf(
            rect(0.08f, 0.10f, 0.18f, 0.18f),
            rect(0.10f, 0.26f, 0.28f, 0.40f),
            rect(0.10f, 0.58f, 0.22f, 0.72f),
            rect(0.28f, 0.10f, 0.42f, 0.20f),
            rect(0.28f, 0.28f, 0.40f, 0.42f),
            rect(0.32f, 0.62f, 0.46f, 0.74f),
            rect(0.58f, 0.42f, 0.72f, 0.58f),
            rect(0.62f, 0.62f, 0.72f, 0.74f),
            rect(0.78f, 0.42f, 0.92f, 0.56f),
            rect(0.80f, 0.62f, 0.90f, 0.76f),
        )
        blocks.forEach { canvas.drawRoundRect(it, dp(12f), dp(12f), blockPaint) }
    }

    private fun drawMapLabels(canvas: Canvas) {
        canvas.drawText("广场北路", px(0.60f), py(0.17f), labelPaint)
        canvas.drawText("汇文路", px(0.36f), py(0.50f), labelPaint)
        canvas.drawText("中心广场", px(0.66f), py(0.28f), labelPaint)
        canvas.drawText("和平大道", px(0.18f), py(0.80f), labelPaint)
    }

    private fun drawSearchCenter(canvas: Canvas) {
        val cx = px(0.52f)
        val cy = py(0.46f)
        canvas.drawCircle(cx, cy, dp(18f), blueDotHaloPaint)
        canvas.drawCircle(cx, cy, dp(10f), blueDotPaint)
    }

    private fun drawHintPulse(canvas: Canvas) {
        val cx = px(0.52f)
        val cy = py(0.46f)
        val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#223B82F6")
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
        }
        canvas.drawCircle(cx, cy, dp(24f), pulsePaint)
    }

    private fun drawRoute(canvas: Canvas) {
        val path = Path().apply {
            moveTo(px(0.40f), py(0.84f))
            lineTo(px(0.40f), py(0.70f))
            lineTo(px(0.56f), py(0.70f))
            lineTo(px(0.56f), py(0.56f))
            lineTo(px(0.74f), py(0.56f))
            lineTo(px(0.74f), py(0.34f))
        }
        routeShadowPaint.strokeWidth = dp(16f)
        routePaint.strokeWidth = dp(10f)
        canvas.drawPath(path, routeShadowPaint)
        canvas.drawPath(path, routePaint)
    }

    private fun drawCurrentMarker(canvas: Canvas) {
        val cx = px(0.40f)
        val cy = py(0.84f)
        canvas.drawCircle(cx, cy, dp(22f), whitePaint)
        canvas.drawCircle(cx, cy, dp(18f), blueDotHaloPaint)
        val arrow = Path().apply {
            moveTo(cx, cy - dp(10f))
            lineTo(cx - dp(7f), cy + dp(9f))
            lineTo(cx, cy + dp(4f))
            lineTo(cx + dp(7f), cy + dp(9f))
            close()
        }
        canvas.drawPath(arrow, blueDotPaint)
    }

    private fun drawTargetMarker(canvas: Canvas) {
        val cx = px(0.74f)
        val cy = py(0.34f)
        canvas.drawCircle(cx, cy, dp(16f), orangePaint)
        canvas.drawCircle(cx, cy, dp(7f), whitePaint)
        canvas.drawRect(cx - dp(3f), cy + dp(10f), cx + dp(3f), cy + dp(20f), orangePaint)
    }

    private fun drawTargetBubble(canvas: Canvas) {
        val title = targetTitle?.takeIf { it.isNotBlank() } ?: "推荐入口"
        val subtitle = entranceLabel?.takeIf { it.isNotBlank() } ?: "北入口"
        val bubbleWidth = min(width * 0.42f, dp(180f))
        val bubble = RectF(
            px(0.53f),
            py(0.42f),
            px(0.53f) + bubbleWidth,
            py(0.42f) + dp(58f),
        )
        canvas.drawRoundRect(bubble, dp(18f), dp(18f), bubblePaint)
        canvas.drawRoundRect(bubble, dp(18f), dp(18f), bubbleStrokePaint)
        canvas.drawText(title, bubble.left + dp(14f), bubble.top + dp(24f), titlePaint)
        canvas.drawText(subtitle, bubble.left + dp(14f), bubble.top + dp(44f), labelPaint)
    }

    private fun rect(left: Float, top: Float, right: Float, bottom: Float): RectF {
        return RectF(px(left), py(top), px(right), py(bottom))
    }

    private fun px(ratio: Float): Float = width * ratio

    private fun py(ratio: Float): Float = height * ratio

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    enum class Mode {
        SEARCH_HOME,
        SEARCH_RESULTS,
        SELECTION,
        ROUTE_READY,
        NAVIGATING,
        HANDOFF,
    }
}
