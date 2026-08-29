package com.aiglasses.poc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextUtils
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View

class IndoorFallbackMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private var currentPoint: IndoorPreviewPoint? = null
    private var targetPoint: IndoorPreviewPoint? = null
    private var completedRoutePoints: List<IndoorPreviewPoint> = emptyList()
    private var pendingRoutePoints: List<IndoorPreviewPoint> = emptyList()
    private var floorLabel: String = "F2"
    private var targetLabel: String = "星巴克（中心店）"

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F6F5F1")
        style = Paint.Style.FILL
    }
    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8F6F2")
        style = Paint.Style.FILL
    }
    private val roomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E4E8EE")
        style = Paint.Style.FILL
    }
    private val hallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.FILL
    }
    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D7DDE5")
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val routeShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFDFD")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(16f)
    }
    private val pendingRoutePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#39A7E4")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(10f)
    }
    private val completedRoutePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B5BDC9")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(10f)
    }
    private val activeFloorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FDFDFB")
        style = Paint.Style.FILL
    }
    private val activeFloorStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#15000000")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val activeFloorTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C86A3")
        textSize = 14f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    private val floorTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4B5563")
        textSize = 14f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#374151")
        textSize = 15f * resources.displayMetrics.scaledDensity
    }
    private val titleTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#374151")
        textSize = 15f * resources.displayMetrics.scaledDensity
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FDFDFB")
        style = Paint.Style.FILL
    }
    private val badgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#15000000")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F58A34")
        style = Paint.Style.FILL
    }
    private val currentHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2F80ED")
        style = Paint.Style.FILL
    }

    fun render(
        current: IndoorPreviewPoint?,
        target: IndoorPreviewPoint?,
        completedRoute: List<IndoorPreviewPoint>,
        pendingRoute: List<IndoorPreviewPoint>,
        floorLabel: String,
        targetLabel: String,
    ) {
        currentPoint = current
        targetPoint = target
        completedRoutePoints = completedRoute
        pendingRoutePoints = pendingRoute
        this.floorLabel = floorLabel
        this.targetLabel = targetLabel
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        drawShell(canvas)
        drawRooms(canvas)
        drawFloorTabs(canvas)
        drawLabels(canvas)
        drawRoute(canvas, completedRoutePoints, completedRoutePaint)
        drawRoute(canvas, pendingRoutePoints, pendingRoutePaint)
        drawCurrentMarker(canvas)
        drawTargetMarker(canvas)
        drawTargetBadge(canvas)
    }

    private fun drawShell(canvas: Canvas) {
        canvas.drawRoundRect(rect(0.06f, 0.16f, 0.88f, 0.92f), dp(28f), dp(28f), shellPaint)
        val hall = Path().apply {
            moveTo(px(0.34f), py(0.82f))
            lineTo(px(0.34f), py(0.42f))
            lineTo(px(0.58f), py(0.42f))
            lineTo(px(0.58f), py(0.24f))
            lineTo(px(0.72f), py(0.24f))
            lineTo(px(0.72f), py(0.78f))
            lineTo(px(0.54f), py(0.78f))
            lineTo(px(0.54f), py(0.88f))
            lineTo(px(0.18f), py(0.88f))
            lineTo(px(0.18f), py(0.82f))
            close()
        }
        canvas.drawPath(hall, hallPaint)
        canvas.drawPath(hall, wallPaint)
    }

    private fun drawRooms(canvas: Canvas) {
        val rooms = listOf(
            rect(0.10f, 0.28f, 0.30f, 0.56f),
            rect(0.10f, 0.60f, 0.28f, 0.84f),
            rect(0.40f, 0.18f, 0.52f, 0.38f),
            rect(0.40f, 0.56f, 0.52f, 0.84f),
            rect(0.62f, 0.18f, 0.78f, 0.34f),
            rect(0.62f, 0.40f, 0.78f, 0.54f),
            rect(0.62f, 0.60f, 0.84f, 0.88f),
        )
        rooms.forEach {
            canvas.drawRoundRect(it, dp(10f), dp(10f), roomPaint)
            canvas.drawRoundRect(it, dp(10f), dp(10f), wallPaint)
        }
    }

    private fun drawFloorTabs(canvas: Canvas) {
        val floors = listOf("F3", "F2", "F1")
        floors.forEachIndexed { index, label ->
            val centerY = py(0.32f + index * 0.12f)
            if (label == floorLabel) {
                val rect = RectF(px(0.84f), centerY - dp(24f), px(0.92f), centerY + dp(24f))
                canvas.drawRoundRect(rect, dp(18f), dp(18f), activeFloorPaint)
                canvas.drawRoundRect(rect, dp(18f), dp(18f), activeFloorStrokePaint)
                canvas.drawText(label, rect.centerX(), centerY + dp(5f), activeFloorTextPaint)
            } else {
                canvas.drawText(label, px(0.88f), centerY + dp(5f), floorTextPaint)
            }
        }
    }

    private fun drawLabels(canvas: Canvas) {
        canvas.drawText("优衣库", px(0.12f), py(0.46f), titlePaint)
        canvas.drawText("ZARA", px(0.12f), py(0.64f), titlePaint)
        canvas.drawText("电梯厅", px(0.62f), py(0.78f), titlePaint)
    }

    private fun drawRoute(canvas: Canvas, points: List<IndoorPreviewPoint>, paint: Paint) {
        if (points.size < 2) {
            return
        }
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = point.toCanvasX(width)
            val y = point.toCanvasY(height)
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, routeShadowPaint)
        canvas.drawPath(path, paint)
    }

    private fun drawCurrentMarker(canvas: Canvas) {
        val point = currentPoint ?: return
        val cx = point.toCanvasX(width)
        val cy = point.toCanvasY(height)
        canvas.drawCircle(cx, cy, dp(22f), currentHaloPaint)
        val arrow = Path().apply {
            moveTo(cx, cy - dp(11f))
            lineTo(cx - dp(8f), cy + dp(10f))
            lineTo(cx, cy + dp(5f))
            lineTo(cx + dp(8f), cy + dp(10f))
            close()
        }
        canvas.drawPath(arrow, currentPaint)
    }

    private fun drawTargetMarker(canvas: Canvas) {
        val point = targetPoint ?: return
        val cx = point.toCanvasX(width)
        val cy = point.toCanvasY(height)
        canvas.drawCircle(cx, cy, dp(16f), targetPaint)
        canvas.drawCircle(cx, cy, dp(7f), currentHaloPaint)
        canvas.drawRect(cx - dp(3f), cy + dp(10f), cx + dp(3f), cy + dp(18f), targetPaint)
    }

    private fun drawTargetBadge(canvas: Canvas) {
        val point = targetPoint ?: return
        val maxBadgeWidth = (width - dp(32f)).coerceAtLeast(dp(120f))
        val displayLabel = TextUtils.ellipsize(
            targetLabel,
            titleTextPaint,
            maxBadgeWidth - dp(24f),
            TextUtils.TruncateAt.END,
        ).toString()
        val badgeWidth = (titlePaint.measureText(displayLabel) + dp(24f)).coerceIn(dp(120f), maxBadgeWidth)
        val anchorX = point.toCanvasX(width)
        val left = (anchorX - badgeWidth / 2f).coerceIn(dp(16f), width - dp(16f) - badgeWidth)
        val top = point.toCanvasY(height) + dp(20f)
        val badge = RectF(left, top, left + badgeWidth, top + dp(34f))
        canvas.drawRoundRect(badge, dp(14f), dp(14f), badgePaint)
        canvas.drawRoundRect(badge, dp(14f), dp(14f), badgeStrokePaint)
        canvas.drawText(displayLabel, badge.left + dp(12f), badge.top + dp(22f), titlePaint)
    }

    private fun rect(left: Float, top: Float, right: Float, bottom: Float): RectF {
        return RectF(px(left), py(top), px(right), py(bottom))
    }

    private fun px(ratio: Float): Float = width * ratio

    private fun py(ratio: Float): Float = height * ratio

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun IndoorPreviewPoint.toCanvasX(canvasWidth: Int): Float {
        return ((x / INDOOR_PREVIEW_MAX_X).coerceIn(0.0, 1.0) * canvasWidth).toFloat()
    }

    private fun IndoorPreviewPoint.toCanvasY(canvasHeight: Int): Float {
        return ((1.0 - (y / INDOOR_PREVIEW_MAX_Y).coerceIn(0.0, 1.0)) * canvasHeight).toFloat()
    }

    companion object {
        private const val INDOOR_PREVIEW_MAX_X = 50.0
        private const val INDOOR_PREVIEW_MAX_Y = 30.0
    }
}
