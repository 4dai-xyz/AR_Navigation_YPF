package com.aiglasses.poc.indoor

import kotlin.math.abs

object ImageIndoorManualDemoScriptBuilder {
    fun build(
        plan: ImageIndoorRoutePlan,
        venueId: String,
        targetPoiId: String,
        targetLabel: String,
    ): ManualIndoorDemoScript {
        return ManualIndoorDemoScript(
            routeId = "image_nav_${plan.start.nodeId}_to_${plan.target.nodeId}",
            venueId = venueId,
            targetPoiId = targetPoiId,
            target = plan.target.toManualPoint(targetLabel),
            steps = buildSteps(plan, targetLabel),
        )
    }

    private fun buildSteps(plan: ImageIndoorRoutePlan, targetLabel: String): List<ManualIndoorDemoStep> {
        if (plan.nodes.size <= 1 || plan.edges.isEmpty()) {
            return listOf(arrivalStep(plan.target, targetLabel, 1))
        }
        val steps = mutableListOf<ManualIndoorDemoStep>()
        var segmentStart = plan.nodes.first()
        var segmentEnd = plan.nodes.first()
        var segmentAction: ManualIndoorDemoAction? = null
        var segmentMode: String? = null
        var segmentDistanceMeters = 0.0
        var segmentDurationSeconds = 0.0

        fun flushSegment() {
            val action = segmentAction ?: return
            val stepNumber = steps.size + 1
            steps.add(
                ManualIndoorDemoStep(
                    id = "image_step_${stepNumber.toString().padStart(3, '0')}",
                    instruction = instructionFor(action, segmentStart, segmentEnd, segmentMode),
                    expectedAction = action,
                    current = segmentStart.toManualPoint(segmentStart.displayLabel(stepNumber)),
                    distanceMeters = segmentDistanceMeters,
                    durationSeconds = segmentDurationSeconds,
                ),
            )
        }

        plan.edges.forEachIndexed { index, edge ->
            val from = plan.nodes[index]
            val to = plan.nodes[index + 1]
            val action = actionFor(edge, from, to)
            val canMerge = segmentAction == action &&
                segmentMode == edge.travelMode &&
                edge.travelMode == "walk" &&
                segmentStart.floorId == from.floorId &&
                from.floorId == to.floorId
            if (segmentAction == null) {
                segmentStart = from
                segmentEnd = to
                segmentAction = action
                segmentMode = edge.travelMode
                segmentDistanceMeters = edge.distance
                segmentDurationSeconds = edge.costSeconds
            } else if (canMerge) {
                segmentEnd = to
                segmentDistanceMeters += edge.distance
                segmentDurationSeconds += edge.costSeconds
            } else {
                flushSegment()
                segmentStart = from
                segmentEnd = to
                segmentAction = action
                segmentMode = edge.travelMode
                segmentDistanceMeters = edge.distance
                segmentDurationSeconds = edge.costSeconds
            }
        }
        flushSegment()
        steps.add(arrivalStep(plan.target, targetLabel, steps.size + 1))
        return steps
    }

    private fun arrivalStep(
        node: ImageIndoorNavNode,
        targetLabel: String,
        stepNumber: Int,
    ): ManualIndoorDemoStep {
        return ManualIndoorDemoStep(
            id = "image_step_${stepNumber.toString().padStart(3, '0')}",
            instruction = "已到达$targetLabel",
            expectedAction = null,
            current = node.toManualPoint(targetLabel),
        )
    }

    private fun actionFor(
        edge: ImageIndoorNavEdge,
        from: ImageIndoorNavNode,
        to: ImageIndoorNavNode,
    ): ManualIndoorDemoAction {
        if (from.floorId != to.floorId || edge.travelMode != "walk") {
            return if (floorRank(to.floorId) > floorRank(from.floorId)) {
                ManualIndoorDemoAction.FLOOR_UP
            } else {
                ManualIndoorDemoAction.FLOOR_DOWN
            }
        }
        val deltaX = to.x - from.x
        val deltaY = to.y - from.y
        return if (abs(deltaX) > abs(deltaY) * 1.2) {
            if (deltaX > 0.0) ManualIndoorDemoAction.RIGHT else ManualIndoorDemoAction.LEFT
        } else {
            ManualIndoorDemoAction.UP
        }
    }

    private fun instructionFor(
        action: ManualIndoorDemoAction,
        from: ImageIndoorNavNode,
        to: ImageIndoorNavNode,
        travelMode: String?,
    ): String {
        return when (action) {
            ManualIndoorDemoAction.FLOOR_UP -> "从 ${from.floorId} 上楼到 ${to.floorId}"
            ManualIndoorDemoAction.FLOOR_DOWN -> "从 ${from.floorId} 下楼到 ${to.floorId}"
            ManualIndoorDemoAction.LEFT -> "在 ${from.floorId} 左转，前往 ${to.displayLabel()}"
            ManualIndoorDemoAction.RIGHT -> "在 ${from.floorId} 右转，前往 ${to.displayLabel()}"
            ManualIndoorDemoAction.UP -> {
                val mode = if (travelMode == "walk") "沿路线直行" else "继续前进"
                "$mode，前往 ${to.displayLabel()}"
            }
            ManualIndoorDemoAction.BACK -> "回退一步"
        }
    }

    private fun ImageIndoorNavNode.toManualPoint(label: String): ManualIndoorDemoPoint {
        return ManualIndoorDemoPoint(
            label = label,
            floorId = floorId,
            x = x,
            y = y,
            nodeId = nodeId,
        )
    }

    private fun ImageIndoorNavNode.displayLabel(stepNumber: Int? = null): String {
        val prefix = stepNumber?.let { "路线点 $it · " }.orEmpty()
        return "$prefix$floorId ${nodeType.ifBlank { nodeId }}"
    }

    private fun floorRank(floorId: String): Int {
        val normalized = floorId.trim().uppercase()
        return when {
            normalized.startsWith("B") -> -(normalized.drop(1).toIntOrNull() ?: 0)
            normalized.startsWith("F") -> normalized.drop(1).toIntOrNull() ?: 0
            else -> normalized.toIntOrNull() ?: 0
        }
    }
}
