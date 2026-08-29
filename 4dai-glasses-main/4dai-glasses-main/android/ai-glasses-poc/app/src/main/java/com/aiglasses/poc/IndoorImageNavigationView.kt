package com.aiglasses.poc

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.aiglasses.poc.indoor.ImageIndoorCoordinateMapper
import com.aiglasses.poc.indoor.ImageIndoorFloor
import com.aiglasses.poc.indoor.ImageIndoorNavNode
import com.aiglasses.poc.indoor.ImageIndoorRoutePlan

class IndoorImageNavigationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val bitmapCache = mutableMapOf<String, Bitmap>()
    private var floor: ImageIndoorFloor? = null
    private var imageAssetPath: String? = null
    private var routePlan: ImageIndoorRoutePlan? = null
    private var currentPosition: ImagePoint? = null
    private var currentPositionTarget: ImagePoint? = null
    private var currentPositionAnimator: ValueAnimator? = null
    private var viewportZoom = 1f
    private var viewportPanX = 0f
    private var viewportPanY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var pendingFocus: FocusRequest? = null
    var onDisplayedCurrentPositionChanged: ((floorId: String?, x: Double?, y: Double?) -> Unit)? = null
    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomAt(detector.focusX, detector.focusY, detector.scaleFactor)
                return true
            }
        },
    )

    private val routeShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 17, 24, 39)
        strokeWidth = 14f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(37, 99, 235)
        strokeWidth = 9f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(22, 163, 74)
        style = Paint.Style.FILL
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 38, 38)
        style = Paint.Style.FILL
    }
    private val targetHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(55, 220, 38, 38)
        style = Paint.Style.FILL
    }
    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(14, 165, 233)
        style = Paint.Style.FILL
    }
    private val currentHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(65, 14, 165, 233)
        style = Paint.Style.FILL
    }
    private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(17, 24, 39)
        textSize = 25f
        isFakeBoldText = true
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(246, 245, 241)
        style = Paint.Style.FILL
    }

    init {
        isClickable = true
    }

    fun render(
        floor: ImageIndoorFloor,
        imageAssetPath: String,
        routePlan: ImageIndoorRoutePlan,
    ) {
        this.floor = floor
        this.imageAssetPath = imageAssetPath
        this.routePlan = routePlan
        invalidate()
    }

    @Suppress("UNUSED_PARAMETER")
    fun setCurrentPosition(
        floorId: String?,
        x: Double?,
        y: Double?,
        headingDeg: Double? = null,
        confidence: Double? = null,
    ) {
        val nextPosition = if (floorId != null && x != null && y != null) {
            ImagePoint(floorId = floorId, x = x, y = y)
        } else {
            null
        }
        updateCurrentPosition(nextPosition, confidence)
    }

    @Suppress("UNUSED_PARAMETER")
    fun setCurrentHeadingDegrees(headingDeg: Double?) {
    }

    fun focusRouteTarget(zoom: Float = AUTO_FOCUS_ZOOM) {
        routePlan?.target?.let { focusImagePoint(it.x, it.y, zoom) }
    }

    fun focusCurrentPosition(zoom: Float = AUTO_FOCUS_ZOOM) {
        currentPosition?.let { focusImagePoint(it.x, it.y, zoom) }
    }

    fun resetViewport() {
        viewportZoom = 1f
        viewportPanX = 0f
        viewportPanY = 0f
        pendingFocus = null
        invalidate()
    }

    fun renderBasemap(imageAssetPath: String) {
        floor = null
        this.imageAssetPath = imageAssetPath
        routePlan = null
        invalidate()
    }

    fun renderBasemap(floor: ImageIndoorFloor, imageAssetPath: String) {
        this.floor = floor
        this.imageAssetPath = imageAssetPath
        routePlan = null
        invalidate()
    }

    fun clear() {
        floor = null
        imageAssetPath = null
        routePlan = null
        currentPositionAnimator?.cancel()
        currentPositionAnimator = null
        currentPosition = null
        currentPositionTarget = null
        notifyDisplayedCurrentPositionChanged()
        resetViewport()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleGestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleGestureDetector.isInProgress && event.pointerCount == 1) {
                    viewportPanX += event.x - lastTouchX
                    viewportPanY += event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (event.pointerCount > 0) {
                    val index = if (event.actionIndex == 0 && event.pointerCount > 1) 1 else 0
                    lastTouchX = event.getX(index.coerceAtMost(event.pointerCount - 1))
                    lastTouchY = event.getY(index.coerceAtMost(event.pointerCount - 1))
                }
                return true
            }
        }
        return true
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        applyPendingFocusIfReady()
    }

    private fun focusImagePoint(x: Double, y: Double, zoom: Float) {
        pendingFocus = FocusRequest(x = x, y = y, zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM))
        applyPendingFocusIfReady()
    }

    private fun applyPendingFocusIfReady() {
        val request = pendingFocus ?: return
        val currentFloor = floor
        val currentPath = imageAssetPath ?: return
        val bitmap = loadBitmap(currentPath) ?: return
        val imageWidth = currentFloor?.width ?: bitmap.width
        val imageHeight = currentFloor?.height ?: bitmap.height
        if (width <= 0 || height <= 0 || imageWidth <= 0 || imageHeight <= 0) return
        val basePoint = baseScreenPoint(request.x, request.y, imageWidth, imageHeight)
        viewportZoom = request.zoom
        viewportPanX = width / 2f - (width / 2f + (basePoint.x - width / 2f) * viewportZoom)
        viewportPanY = height / 2f - (height / 2f + (basePoint.y - height / 2f) * viewportZoom)
        pendingFocus = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentPath = imageAssetPath ?: return
        val bitmap = loadBitmap(currentPath) ?: return
        val frame = imageFrame(floor?.width ?: bitmap.width, floor?.height ?: bitmap.height)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawBitmap(bitmap, null, frame, null)

        val currentFloor = floor ?: return
        routePlan?.let { currentPlan ->
            val segments = currentPlan.walkSegmentsForFloor(currentFloor.floorId)
            segments.forEach { segment ->
                drawRouteSegment(canvas, currentFloor, segment, routeShadowPaint)
                drawRouteSegment(canvas, currentFloor, segment, routePaint)
            }
            drawMarker(canvas, currentFloor, currentPlan.start, startPaint, "起点")
            drawTargetMarker(canvas, currentFloor, currentPlan.target)
        }
        drawCurrentPosition(canvas, currentFloor)
    }

    private fun drawRouteSegment(
        canvas: Canvas,
        floor: ImageIndoorFloor,
        segment: List<ImageIndoorNavNode>,
        paint: Paint,
    ) {
        if (segment.size < 2) {
            return
        }
        val path = Path()
        segment.forEachIndexed { index, node ->
            val point = node.toScreenPoint(floor)
            if (index == 0) {
                path.moveTo(point.x, point.y)
            } else {
                path.lineTo(point.x, point.y)
            }
        }
        canvas.drawPath(path, paint)
    }

    private fun drawMarker(
        canvas: Canvas,
        floor: ImageIndoorFloor,
        node: ImageIndoorNavNode,
        paint: Paint,
        label: String,
    ) {
        if (node.floorId != floor.floorId) {
            return
        }
        val point = node.toScreenPoint(floor)
        canvas.drawCircle(point.x, point.y, 17f, paint)
        canvas.drawCircle(point.x, point.y, 17f, markerStrokePaint)
        canvas.drawText(label, point.x + 24f, point.y - 18f, labelPaint)
    }

    private fun drawCurrentPosition(canvas: Canvas, floor: ImageIndoorFloor) {
        val current = currentPosition ?: return
        if (current.floorId != floor.floorId) return
        val point = screenPoint(
            nodeX = current.x,
            nodeY = current.y,
            imageWidth = floor.width,
            imageHeight = floor.height,
        )
        canvas.drawCircle(point.x, point.y, 23f, currentHaloPaint)
        canvas.drawCircle(point.x, point.y, 14f, currentPaint)
        canvas.drawCircle(point.x, point.y, 14f, markerStrokePaint)
    }

    private fun drawTargetMarker(canvas: Canvas, floor: ImageIndoorFloor, node: ImageIndoorNavNode) {
        if (node.floorId != floor.floorId) {
            return
        }
        val point = node.toScreenPoint(floor)
        canvas.drawCircle(point.x, point.y, 26f, targetHaloPaint)
        val pin = Path().apply {
            moveTo(point.x, point.y + 27f)
            cubicTo(point.x - 23f, point.y - 2f, point.x - 18f, point.y - 26f, point.x, point.y - 26f)
            cubicTo(point.x + 18f, point.y - 26f, point.x + 23f, point.y - 2f, point.x, point.y + 27f)
            close()
        }
        canvas.drawPath(pin, targetPaint)
        canvas.drawPath(pin, markerStrokePaint)
        canvas.drawCircle(point.x, point.y - 7f, 7f, markerStrokePaint)
        canvas.drawText("目标", point.x + 24f, point.y - 22f, labelPaint)
    }

    private fun ImageIndoorNavNode.toScreenPoint(floor: ImageIndoorFloor) =
        screenPoint(
            nodeX = x,
            nodeY = y,
            imageWidth = floor.width,
            imageHeight = floor.height,
        )

    private fun imageFrame(imageWidthPx: Int, imageHeightPx: Int): RectF {
        val mappedTopLeft = ImageIndoorCoordinateMapper.mapToScreen(
            nodeX = 0.0,
            nodeY = 0.0,
            imageWidth = imageWidthPx,
            imageHeight = imageHeightPx,
            viewWidth = width,
            viewHeight = height,
        )
        val imageWidth = imageWidthPx * mappedTopLeft.scale
        val imageHeight = imageHeightPx * mappedTopLeft.scale
        return RectF(
            mappedTopLeft.offsetX,
            mappedTopLeft.offsetY,
            mappedTopLeft.offsetX + imageWidth,
            mappedTopLeft.offsetY + imageHeight,
        ).transformViewport()
    }

    private fun screenPoint(
        nodeX: Double,
        nodeY: Double,
        imageWidth: Int,
        imageHeight: Int,
    ) = baseScreenPoint(nodeX, nodeY, imageWidth, imageHeight).transformViewport()

    private fun baseScreenPoint(
        nodeX: Double,
        nodeY: Double,
        imageWidth: Int,
        imageHeight: Int,
    ) = ImageIndoorCoordinateMapper.mapToScreen(
        nodeX = nodeX,
        nodeY = nodeY,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        viewWidth = width,
        viewHeight = height,
    )

    private fun com.aiglasses.poc.indoor.ImageIndoorScreenPoint.transformViewport() =
        copy(
            x = transformX(x),
            y = transformY(y),
        )

    private fun RectF.transformViewport(): RectF {
        return RectF(
            transformX(left),
            transformY(top),
            transformX(right),
            transformY(bottom),
        )
    }

    private fun transformX(x: Float): Float = width / 2f + (x - width / 2f) * viewportZoom + viewportPanX

    private fun transformY(y: Float): Float = height / 2f + (y - height / 2f) * viewportZoom + viewportPanY

    private fun zoomAt(focusX: Float, focusY: Float, scaleFactor: Float) {
        val previousZoom = viewportZoom
        val nextZoom = (viewportZoom * scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (nextZoom == previousZoom) return
        val ratio = nextZoom / previousZoom
        viewportPanX = focusX - width / 2f - (focusX - width / 2f - viewportPanX) * ratio
        viewportPanY = focusY - height / 2f - (focusY - height / 2f - viewportPanY) * ratio
        viewportZoom = nextZoom
        invalidate()
    }

    private fun loadBitmap(assetPath: String): Bitmap? {
        bitmapCache[assetPath]?.let { return it }
        return runCatching {
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()?.also { bitmap ->
            bitmapCache[assetPath] = bitmap
        }
    }

    private fun updateCurrentPosition(nextPosition: ImagePoint?, confidence: Double?) {
        if (nextPosition == null) {
            currentPositionAnimator?.cancel()
            currentPositionAnimator = null
            currentPosition = null
            currentPositionTarget = null
            notifyDisplayedCurrentPositionChanged()
            invalidate()
            return
        }
        if (currentPositionTarget == nextPosition && currentPositionAnimator?.isRunning == true) {
            return
        }
        val displayed = currentPosition
        if (displayed == null || displayed.floorId != nextPosition.floorId) {
            currentPositionAnimator?.cancel()
            currentPositionAnimator = null
            currentPosition = nextPosition
            currentPositionTarget = nextPosition
            notifyDisplayedCurrentPositionChanged()
            invalidate()
            return
        }
        if (displayed.distanceTo(nextPosition) < MIN_CURRENT_POSITION_MOVE_DISTANCE) {
            currentPositionTarget = nextPosition
            return
        }
        if (shouldRejectLargePositionJump(displayed, nextPosition, confidence)) {
            return
        }
        animateCurrentPosition(displayed, nextPosition)
    }

    private fun shouldRejectLargePositionJump(
        from: ImagePoint,
        to: ImagePoint,
        confidence: Double?,
    ): Boolean {
        val imageDiagonal = floor
            ?.takeIf { it.floorId == to.floorId }
            ?.let { kotlin.math.hypot(it.width.toDouble(), it.height.toDouble()) }
        val threshold = imageDiagonal
            ?.let { it * LARGE_JUMP_FLOOR_DIAGONAL_RATIO }
            ?: LARGE_JUMP_FALLBACK_DISTANCE
        return from.distanceTo(to) > threshold && (confidence ?: 0.0) < HIGH_CONFIDENCE_FOR_LARGE_JUMP
    }

    private fun animateCurrentPosition(from: ImagePoint, to: ImagePoint) {
        currentPositionAnimator?.cancel()
        currentPositionTarget = to
        currentPositionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CURRENT_POSITION_ANIMATION_MS
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                currentPosition = from.interpolate(to, progress.toDouble())
                notifyDisplayedCurrentPositionChanged()
                invalidate()
            }
            start()
        }
    }

    private fun notifyDisplayedCurrentPositionChanged() {
        val current = currentPosition
        onDisplayedCurrentPositionChanged?.invoke(current?.floorId, current?.x, current?.y)
    }

    private data class ImagePoint(
        val floorId: String,
        val x: Double,
        val y: Double,
    ) {
        fun distanceTo(other: ImagePoint): Double {
            return kotlin.math.hypot(x - other.x, y - other.y)
        }

        fun interpolate(other: ImagePoint, progress: Double): ImagePoint {
            return ImagePoint(
                floorId = floorId,
                x = x + (other.x - x) * progress,
                y = y + (other.y - y) * progress,
            )
        }
    }

    private data class FocusRequest(
        val x: Double,
        val y: Double,
        val zoom: Float,
    )

    companion object {
        private const val MIN_ZOOM = 1f
        private const val MAX_ZOOM = 5f
        private const val AUTO_FOCUS_ZOOM = 2.4f
        private const val CURRENT_POSITION_ANIMATION_MS = 520L
        private const val MIN_CURRENT_POSITION_MOVE_DISTANCE = 1.0
        private const val LARGE_JUMP_FLOOR_DIAGONAL_RATIO = 0.22
        private const val LARGE_JUMP_FALLBACK_DISTANCE = 450.0
        private const val HIGH_CONFIDENCE_FOR_LARGE_JUMP = 0.85
    }
}
