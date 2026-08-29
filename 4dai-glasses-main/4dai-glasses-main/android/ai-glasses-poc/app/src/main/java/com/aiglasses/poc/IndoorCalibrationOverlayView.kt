package com.aiglasses.poc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.aiglasses.poc.indoor.IndoorOverlayPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class IndoorCalibrationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private var points: List<IndoorOverlayPoint> = emptyList()
    private var routeSegments: List<List<IndoorOverlayPoint>> = emptyList()
    private var overlayScaleX = 1f
    private var overlayScaleY = 1f
    private var overlayRotationDegrees = 0f
    private var overlayOffsetX = 0f
    private var overlayOffsetY = 0f
    private var mirrorY = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastPinchDistance = 0f
    private var lastPinchAngle = 0f
    private var lastPinchCenterX = 0f
    private var lastPinchCenterY = 0f
    private var pointBounds = PointBounds.empty()
    private var isLargeGraph = false
    private val routePath = Path()

    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(245, 158, 11)
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val routeShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 17, 24, 39)
        strokeWidth = 12f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val f1PointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(249, 115, 22)
        style = Paint.Style.FILL
    }
    private val f2PointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(139, 92, 246)
        style = Paint.Style.FILL
    }
    private val pointStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(17, 24, 39)
        textSize = 24f
        isFakeBoldText = true
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(17, 24, 39)
        textSize = 24f
    }
    private val hintBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        style = Paint.Style.FILL
    }

    fun render(
        points: List<IndoorOverlayPoint>,
        routeSegments: List<List<IndoorOverlayPoint>>,
    ) {
        this.points = points
        this.routeSegments = routeSegments
        this.pointBounds = PointBounds.from(points)
        this.isLargeGraph = points.size > MAX_LABELED_POINTS
        postInvalidateOnAnimation()
    }

    fun resetTransform() {
        overlayScaleX = 1f
        overlayScaleY = 1f
        overlayRotationDegrees = 0f
        overlayOffsetX = 0f
        overlayOffsetY = 0f
        postInvalidateOnAnimation()
    }

    fun toggleMirrorY(): Boolean {
        mirrorY = !mirrorY
        postInvalidateOnAnimation()
        return mirrorY
    }

    fun isMirrorYEnabled(): Boolean = mirrorY

    fun rotateBy(degrees: Float) {
        overlayRotationDegrees = normalizeRotation(overlayRotationDegrees + degrees)
        postInvalidateOnAnimation()
    }

    fun scaleXBy(factor: Float) {
        overlayScaleX = (overlayScaleX * factor).coerceIn(MIN_AXIS_SCALE, MAX_AXIS_SCALE)
        postInvalidateOnAnimation()
    }

    fun scaleYBy(factor: Float) {
        overlayScaleY = (overlayScaleY * factor).coerceIn(MIN_AXIS_SCALE, MAX_AXIS_SCALE)
        postInvalidateOnAnimation()
    }

    fun transformSummary(): String {
        val mirrorText = if (mirrorY) "已上下翻转" else "未上下翻转"
        return "旋转 ${overlayRotationDegrees.formatDegrees()}°；横向 ${overlayScaleX.formatScale()}；纵向 ${overlayScaleY.formatScale()}；$mirrorText"
    }

    fun currentScreenPoints(): List<IndoorCalibrationScreenPoint> {
        if (width <= 0 || height <= 0) {
            return emptyList()
        }
        return points.mapIndexed { index, point ->
            IndoorCalibrationScreenPoint(
                index = index + 1,
                label = point.label,
                floorId = point.floorId,
                x = point.x,
                y = point.y,
                screenX = point.screenX(),
                screenY = point.screenY(),
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (points.isEmpty()) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    lastPinchDistance = event.pinchDistance()
                    lastPinchAngle = event.pinchAngle()
                    lastPinchCenterX = event.pinchCenterX()
                    lastPinchCenterY = event.pinchCenterY()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val distance = event.pinchDistance()
                    if (lastPinchDistance > 0f && distance > 0f) {
                        val factor = distance / lastPinchDistance
                        overlayScaleX = (overlayScaleX * factor).coerceIn(MIN_AXIS_SCALE, MAX_AXIS_SCALE)
                        overlayScaleY = (overlayScaleY * factor).coerceIn(MIN_AXIS_SCALE, MAX_AXIS_SCALE)
                    }
                    val angle = event.pinchAngle()
                    overlayRotationDegrees = normalizeRotation(overlayRotationDegrees + angle - lastPinchAngle)
                    val centerX = event.pinchCenterX()
                    val centerY = event.pinchCenterY()
                    overlayOffsetX += centerX - lastPinchCenterX
                    overlayOffsetY += centerY - lastPinchCenterY
                    lastPinchDistance = distance
                    lastPinchAngle = angle
                    lastPinchCenterX = centerX
                    lastPinchCenterY = centerY
                } else {
                    overlayOffsetX += event.x - lastTouchX
                    overlayOffsetY += event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                postInvalidateOnAnimation()
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                lastPinchDistance = 0f
                return true
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) {
            return
        }
        drawRoutes(canvas)
        points.forEachIndexed { index, point ->
            drawPoint(canvas, index + 1, point)
        }
        drawHint(canvas)
    }

    private fun drawRoutes(canvas: Canvas) {
        if (routeSegments.isEmpty()) {
            return
        }
        routePath.reset()
        routeSegments.forEach { segment ->
            segment.forEachIndexed { index, point ->
                val x = point.screenX()
                val y = point.screenY()
                if (index == 0) {
                    routePath.moveTo(x, y)
                } else {
                    routePath.lineTo(x, y)
                }
            }
        }
        if (!isLargeGraph) {
            canvas.drawPath(routePath, routeShadowPaint)
        }
        canvas.drawPath(routePath, routePaint)
    }

    private fun drawPoint(canvas: Canvas, index: Int, point: IndoorOverlayPoint) {
        val x = point.screenX()
        val y = point.screenY()
        val radius = if (isLargeGraph) 7f else 18f
        val paint = if (point.floorId.equals("F2", ignoreCase = true)) f2PointPaint else f1PointPaint
        canvas.drawCircle(x, y, radius, paint)
        if (!isLargeGraph) {
            canvas.drawCircle(x, y, radius, pointStrokePaint)
            canvas.drawText(index.toString(), x, y + 8f, textPaint)
            canvas.drawText("${point.floorId} ${point.label}", x + 24f, y - 20f, labelPaint)
        }
    }

    private fun drawHint(canvas: Canvas) {
        val hint = if (mirrorY) {
            "路线校准层：单指拖动，双指缩放/旋转，已上下翻转"
        } else {
            "路线校准层：单指拖动，双指缩放/旋转"
        }
        val left = 16f
        val top = 16f
        val right = left + hintPaint.measureText(hint) + 24f
        val bottom = top + 44f
        canvas.drawRoundRect(left, top, right, bottom, 18f, 18f, hintBackgroundPaint)
        canvas.drawText(hint, left + 12f, top + 30f, hintPaint)
    }

    private fun IndoorOverlayPoint.screenX(): Float {
        val normalized = ((x - pointBounds.minX) / pointBounds.width()).coerceIn(0.0, 1.0).toFloat()
        val baseX = drawingLeft() + normalized * drawingWidth()
        return transformPoint(baseX, screenBaseY()).first
    }

    private fun IndoorOverlayPoint.screenY(): Float {
        return transformPoint(screenBaseX(), screenBaseY()).second
    }

    private fun IndoorOverlayPoint.screenBaseX(): Float {
        val normalized = ((x - pointBounds.minX) / pointBounds.width()).coerceIn(0.0, 1.0).toFloat()
        return drawingLeft() + normalized * drawingWidth()
    }

    private fun IndoorOverlayPoint.screenBaseY(): Float {
        val normalized = ((y - pointBounds.minY) / pointBounds.height()).coerceIn(0.0, 1.0).toFloat()
        val displayY = if (mirrorY) 1f - normalized else normalized
        return drawingTop() + displayY * drawingHeight()
    }

    private fun transformPoint(baseX: Float, baseY: Float): Pair<Float, Float> {
        val scaledX = (baseX - centerX()) * overlayScaleX
        val scaledY = (baseY - centerY()) * overlayScaleY
        val radians = Math.toRadians(overlayRotationDegrees.toDouble())
        val cosValue = cos(radians).toFloat()
        val sinValue = sin(radians).toFloat()
        return Pair(
            centerX() + scaledX * cosValue - scaledY * sinValue + overlayOffsetX,
            centerY() + scaledX * sinValue + scaledY * cosValue + overlayOffsetY,
        )
    }

    private fun drawingWidth(): Float = width * 0.66f
    private fun drawingHeight(): Float = height * 0.66f
    private fun drawingLeft(): Float = (width - drawingWidth()) / 2f
    private fun drawingTop(): Float = (height - drawingHeight()) / 2f
    private fun centerX(): Float = width / 2f
    private fun centerY(): Float = height / 2f

    private fun MotionEvent.pinchDistance(): Float {
        return hypot(getX(0) - getX(1), getY(0) - getY(1))
    }

    private fun MotionEvent.pinchAngle(): Float {
        return Math.toDegrees(
            atan2(
                (getY(1) - getY(0)).toDouble(),
                (getX(1) - getX(0)).toDouble(),
            ),
        ).toFloat()
    }

    private fun MotionEvent.pinchCenterX(): Float {
        return (getX(0) + getX(1)) / 2f
    }

    private fun MotionEvent.pinchCenterY(): Float {
        return (getY(0) + getY(1)) / 2f
    }

    private data class PointBounds(
        val minX: Double,
        val maxX: Double,
        val minY: Double,
        val maxY: Double,
    ) {
        fun width(): Double = (maxX - minX).takeIf { it > 0.0 } ?: 1.0
        fun height(): Double = (maxY - minY).takeIf { it > 0.0 } ?: 1.0

        companion object {
            fun empty(): PointBounds = PointBounds(0.0, 1.0, 0.0, 1.0)

            fun from(points: List<IndoorOverlayPoint>): PointBounds {
                if (points.isEmpty()) {
                    return empty()
                }
                var minX = Double.POSITIVE_INFINITY
                var maxX = Double.NEGATIVE_INFINITY
                var minY = Double.POSITIVE_INFINITY
                var maxY = Double.NEGATIVE_INFINITY
                points.forEach { point ->
                    minX = minOf(minX, point.x)
                    maxX = maxOf(maxX, point.x)
                    minY = minOf(minY, point.y)
                    maxY = maxOf(maxY, point.y)
                }
                return PointBounds(minX, maxX, minY, maxY)
            }
        }
    }

    private companion object {
        private const val MAX_LABELED_POINTS = 80
        private const val MIN_AXIS_SCALE = 0.25f
        private const val MAX_AXIS_SCALE = 6.0f

        private fun normalizeRotation(degrees: Float): Float {
            var normalized = degrees % 360f
            if (normalized > 180f) normalized -= 360f
            if (normalized < -180f) normalized += 360f
            return normalized
        }

        private fun Float.formatScale(): String {
            return String.format(java.util.Locale.US, "%.2f", this)
        }

        private fun Float.formatDegrees(): String {
            return String.format(java.util.Locale.US, "%.1f", this)
        }
    }
}

data class IndoorCalibrationScreenPoint(
    val index: Int,
    val label: String,
    val floorId: String,
    val x: Double,
    val y: Double,
    val screenX: Float,
    val screenY: Float,
)
