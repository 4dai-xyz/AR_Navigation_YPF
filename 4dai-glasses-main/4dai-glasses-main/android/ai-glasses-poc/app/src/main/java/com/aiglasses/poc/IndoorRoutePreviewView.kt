package com.aiglasses.poc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class IndoorRoutePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private var completedRoutePoints: List<IndoorPreviewPoint> = emptyList()
    private var pendingRoutePoints: List<IndoorPreviewPoint> = emptyList()
    private var currentPoint: IndoorPreviewPoint? = null
    private var targetPoint: IndoorPreviewPoint? = null

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(229, 231, 235)
        strokeWidth = 2f
    }
    private val completedRoutePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(156, 163, 175)
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pendingRoutePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(37, 99, 235)
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(22, 163, 74)
        style = Paint.Style.FILL
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 38, 38)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(31, 41, 55)
        textSize = 24f
    }

    fun render(
        current: IndoorPreviewPoint?,
        target: IndoorPreviewPoint?,
        route: List<IndoorPreviewPoint>,
    ) {
        render(
            current = current,
            target = target,
            completedRoute = emptyList(),
            pendingRoute = route,
        )
    }

    fun render(
        current: IndoorPreviewPoint?,
        target: IndoorPreviewPoint?,
        completedRoute: List<IndoorPreviewPoint>,
        pendingRoute: List<IndoorPreviewPoint>,
    ) {
        currentPoint = current
        targetPoint = target
        completedRoutePoints = completedRoute
        pendingRoutePoints = pendingRoute
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGrid(canvas)
        drawRoute(canvas, completedRoutePoints, completedRoutePaint)
        drawRoute(canvas, pendingRoutePoints, pendingRoutePaint)
        drawPoint(canvas, currentPoint, currentPaint, "当前位置")
        drawPoint(canvas, targetPoint, targetPaint, "目标")
    }

    private fun drawGrid(canvas: Canvas) {
        val stepX = width / 4f
        val stepY = height / 3f
        for (index in 1..3) {
            canvas.drawLine(stepX * index, 0f, stepX * index, height.toFloat(), gridPaint)
        }
        for (index in 1..2) {
            canvas.drawLine(0f, stepY * index, width.toFloat(), stepY * index, gridPaint)
        }
    }

    private fun drawRoute(canvas: Canvas, routePoints: List<IndoorPreviewPoint>, paint: Paint) {
        if (routePoints.size < 2) {
            return
        }
        val path = Path()
        routePoints.forEachIndexed { index, point ->
            val x = point.toCanvasX()
            val y = point.toCanvasY()
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, paint)
    }

    private fun drawPoint(canvas: Canvas, point: IndoorPreviewPoint?, paint: Paint, label: String) {
        if (point == null) {
            return
        }
        val x = point.toCanvasX()
        val y = point.toCanvasY()
        canvas.drawCircle(x, y, 14f, paint)
        canvas.drawText(label, x + 18f, (y - 12f).coerceAtLeast(24f), labelPaint)
    }

    private fun IndoorPreviewPoint.toCanvasX(): Float {
        return ((x / INDOOR_PREVIEW_MAX_X).coerceIn(0.0, 1.0) * width).toFloat()
    }

    private fun IndoorPreviewPoint.toCanvasY(): Float {
        return ((1.0 - (y / INDOOR_PREVIEW_MAX_Y).coerceIn(0.0, 1.0)) * height).toFloat()
    }

    companion object {
        private const val INDOOR_PREVIEW_MAX_X = 50.0
        private const val INDOOR_PREVIEW_MAX_Y = 30.0
    }
}

data class IndoorPreviewPoint(
    val x: Double,
    val y: Double,
)
