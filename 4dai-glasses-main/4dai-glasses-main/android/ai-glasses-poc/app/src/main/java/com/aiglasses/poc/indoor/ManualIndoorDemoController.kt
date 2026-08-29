package com.aiglasses.poc.indoor

enum class IndoorNavigationMode(
    val id: String,
) {
    MANUAL_DEMO("manual_demo"),
    CLOUD_RELOCALIZATION("cloud_relocalization"),
}

enum class ManualIndoorDemoAction(
    val label: String,
) {
    UP("直行"),
    LEFT("左转"),
    RIGHT("右转"),
    BACK("回退一步"),
    FLOOR_UP("上楼"),
    FLOOR_DOWN("下楼"),
}

data class ManualIndoorDemoPoint(
    val label: String,
    val floorId: String,
    val x: Double,
    val y: Double,
    val nodeId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

data class ManualIndoorDemoStep(
    val id: String,
    val instruction: String,
    val expectedAction: ManualIndoorDemoAction?,
    val current: ManualIndoorDemoPoint,
    val distanceMeters: Double = 0.0,
    val durationSeconds: Double = 0.0,
)

data class ManualIndoorDemoScript(
    val routeId: String,
    val venueId: String,
    val targetPoiId: String,
    val target: ManualIndoorDemoPoint,
    val steps: List<ManualIndoorDemoStep>,
)

data class ManualIndoorDemoState(
    val routeId: String,
    val venueId: String,
    val targetPoiId: String,
    val stepIndex: Int,
    val totalSteps: Int,
    val currentFloorId: String,
    val currentNodeLabel: String,
    val targetNodeLabel: String,
    val instruction: String,
    val expectedAction: ManualIndoorDemoAction?,
    val correction: String? = null,
    val arrived: Boolean = false,
    val remainingDistanceMeters: Double = 0.0,
    val remainingDurationSeconds: Double = 0.0,
    val current: ManualIndoorDemoPoint,
    val target: ManualIndoorDemoPoint,
    val completedRoute: List<ManualIndoorDemoPoint>,
    val pendingRoute: List<ManualIndoorDemoPoint>,
) {
    val currentStepNumber: Int = stepIndex + 1
}

data class ManualIndoorDemoResult(
    val state: ManualIndoorDemoState,
    val events: List<String>,
)

class ManualIndoorDemoController(
    private val script: ManualIndoorDemoScript = ManualIndoorDemoScripts.defaultScript,
) {
    private var currentIndex = 0
    private var correction: String? = null

    fun start(): ManualIndoorDemoResult {
        currentIndex = 0
        correction = null
        return ManualIndoorDemoResult(
            state = currentState(),
            events = listOf("manual_demo_started"),
        )
    }

    fun reset(): ManualIndoorDemoResult {
        currentIndex = 0
        correction = null
        return ManualIndoorDemoResult(
            state = currentState(),
            events = listOf("manual_demo_reset"),
        )
    }

    fun handle(action: ManualIndoorDemoAction): ManualIndoorDemoResult {
        if (action == ManualIndoorDemoAction.BACK) {
            return stepBack()
        }
        val step = script.steps[currentIndex]
        val expectedAction = step.expectedAction
        if (expectedAction == null) {
            correction = "已到达店铺门口"
            return ManualIndoorDemoResult(currentState(), emptyList())
        }
        if (action != expectedAction) {
            correction = "当前应执行：${expectedAction.label}"
            return ManualIndoorDemoResult(
                state = currentState(),
                events = listOf("manual_demo_wrong_action action=${action.name.lowercase()} expected=${expectedAction.name.lowercase()}"),
            )
        }

        val previousFloor = step.current.floorId
        currentIndex = (currentIndex + 1).coerceAtMost(script.steps.lastIndex)
        correction = null
        val nextState = currentState()
        val events = buildList {
            add("manual_demo_step_advanced action=${action.name.lowercase()} step=${nextState.currentStepNumber}/${nextState.totalSteps}")
            if (nextState.currentFloorId != previousFloor) {
                add("manual_demo_floor_changed from=$previousFloor to=${nextState.currentFloorId}")
            }
            if (nextState.arrived) {
                add("manual_demo_arrived target=${nextState.targetNodeLabel}")
            }
        }
        return ManualIndoorDemoResult(nextState, events)
    }

    fun state(): ManualIndoorDemoState = currentState()

    private fun stepBack(): ManualIndoorDemoResult {
        val previousFloor = script.steps[currentIndex].current.floorId
        currentIndex = (currentIndex - 1).coerceAtLeast(0)
        correction = null
        val nextState = currentState()
        val events = buildList {
            add("manual_demo_step_back step=${nextState.currentStepNumber}/${nextState.totalSteps}")
            if (nextState.currentFloorId != previousFloor) {
                add("manual_demo_floor_changed from=$previousFloor to=${nextState.currentFloorId}")
            }
        }
        return ManualIndoorDemoResult(nextState, events)
    }

    private fun currentState(): ManualIndoorDemoState {
        val step = script.steps[currentIndex]
        val points = script.steps.map { it.current }
        val arrived = step.expectedAction == null
        val currentFloorId = step.current.floorId
        val remainingDistanceMeters = remainingDistanceMeters()
        val remainingDurationSeconds = remainingDurationSeconds(remainingDistanceMeters)
        return ManualIndoorDemoState(
            routeId = script.routeId,
            venueId = script.venueId,
            targetPoiId = script.targetPoiId,
            stepIndex = currentIndex,
            totalSteps = script.steps.size,
            currentFloorId = currentFloorId,
            currentNodeLabel = step.current.label,
            targetNodeLabel = script.target.label,
            instruction = if (arrived) "已到达店铺门口" else step.instruction,
            expectedAction = step.expectedAction,
            correction = correction,
            arrived = arrived,
            remainingDistanceMeters = remainingDistanceMeters,
            remainingDurationSeconds = remainingDurationSeconds,
            current = step.current,
            target = script.target,
            completedRoute = points.take(currentIndex + 1).filter { it.floorId == currentFloorId },
            pendingRoute = points.drop(currentIndex).filter { it.floorId == currentFloorId },
        )
    }

    private fun remainingDistanceMeters(): Double {
        return script.steps.indices.drop(currentIndex).sumOf { index ->
            val step = script.steps[index]
            if (step.expectedAction == null) {
                0.0
            } else {
                step.distanceMeters.takeIf { it > 0.0 }
                    ?: estimateDistanceMeters(step.current, script.steps.getOrNull(index + 1)?.current)
            }
        }
    }

    private fun remainingDurationSeconds(distanceMeters: Double): Double {
        return script.steps.indices.drop(currentIndex).sumOf { index ->
            val step = script.steps[index]
            if (step.expectedAction == null) {
                0.0
            } else {
                step.durationSeconds.takeIf { it > 0.0 }
                    ?: (estimateDistanceMeters(step.current, script.steps.getOrNull(index + 1)?.current) / WALKING_METERS_PER_SECOND)
            }
        }.takeIf { it > 0.0 } ?: (distanceMeters / WALKING_METERS_PER_SECOND)
    }

    private fun estimateDistanceMeters(
        from: ManualIndoorDemoPoint,
        to: ManualIndoorDemoPoint?,
    ): Double {
        if (to == null) {
            return 0.0
        }
        if (from.floorId != to.floorId) {
            return DEFAULT_FLOOR_CHANGE_DISTANCE_METERS
        }
        val dx = from.x - to.x
        val dy = from.y - to.y
        return kotlin.math.hypot(dx, dy).coerceAtLeast(0.0)
    }

    private companion object {
        private const val WALKING_METERS_PER_SECOND = 1.2
        private const val DEFAULT_FLOOR_CHANGE_DISTANCE_METERS = 12.0
    }
}

object ManualIndoorDemoScripts {
    val defaultScript = ManualIndoorDemoScript(
        routeId = "manual_demo_wudaokou_west_to_2f_tata",
        venueId = "venue_bj_wudaokou_shopping_center_demo",
        targetPoiId = "poi_f2_tata_door_demo",
        target = ManualIndoorDemoPoint(
            label = "2F TATA 店铺门口",
            floorId = "F2",
            x = 42.0,
            y = 26.0,
            nodeId = "node_f2_tata_door_demo",
            latitude = 39.991556,
            longitude = 116.339568,
        ),
        steps = listOf(
            ManualIndoorDemoStep(
                id = "step_001",
                instruction = "从西门进入，沿一层通道直行至西侧通道转角",
                expectedAction = ManualIndoorDemoAction.UP,
                current = ManualIndoorDemoPoint(
                    label = "F1 西门入口",
                    floorId = "F1",
                    x = 6.0,
                    y = 23.7,
                    nodeId = "node_f1_west_gate_demo",
                    latitude = 39.991583,
                    longitude = 116.338965,
                ),
            ),
            ManualIndoorDemoStep(
                id = "step_002",
                instruction = "左转后沿主通道前往一层上行扶梯",
                expectedAction = ManualIndoorDemoAction.LEFT,
                current = ManualIndoorDemoPoint(
                    label = "F1 西侧通道转角",
                    floorId = "F1",
                    x = 25.9,
                    y = 23.8,
                    nodeId = "node_f1_west_corridor_turn_demo",
                ),
            ),
            ManualIndoorDemoStep(
                id = "step_003",
                instruction = "乘扶梯上行至二层",
                expectedAction = ManualIndoorDemoAction.FLOOR_UP,
                current = ManualIndoorDemoPoint(
                    label = "F1 上行扶梯口",
                    floorId = "F1",
                    x = 28.9,
                    y = 9.3,
                    nodeId = "node_f1_escalator_up_demo_01",
                    latitude = 39.992093,
                    longitude = 116.339331,
                ),
            ),
            ManualIndoorDemoStep(
                id = "step_004",
                instruction = "到达二层后出扶梯，沿连廊直行至第一个转角",
                expectedAction = ManualIndoorDemoAction.UP,
                current = ManualIndoorDemoPoint(
                    label = "F2 扶梯出口",
                    floorId = "F2",
                    x = 28.4,
                    y = 6.0,
                    nodeId = "node_f2_escalator_out_demo_01",
                    latitude = 39.992189,
                    longitude = 116.339304,
                ),
            ),
            ManualIndoorDemoStep(
                id = "step_005",
                instruction = "右转后沿二层连廊直行，前往 TATA 区域",
                expectedAction = ManualIndoorDemoAction.RIGHT,
                current = ManualIndoorDemoPoint(
                    label = "F2 TATA 连廊转角 01",
                    floorId = "F2",
                    x = 32.5,
                    y = 6.0,
                    nodeId = "node_f2_tata_corridor_turn_01_demo",
                ),
            ),
            ManualIndoorDemoStep(
                id = "step_006",
                instruction = "左前方前往 TATA 店铺门口",
                expectedAction = ManualIndoorDemoAction.LEFT,
                current = ManualIndoorDemoPoint(
                    label = "F2 TATA 连廊转角 02",
                    floorId = "F2",
                    x = 32.8,
                    y = 23.6,
                    nodeId = "node_f2_tata_corridor_turn_02_demo",
                ),
            ),
            ManualIndoorDemoStep(
                id = "step_007",
                instruction = "已到达 TATA 店铺门口",
                expectedAction = null,
                current = ManualIndoorDemoPoint(
                    label = "2F TATA 店铺门口",
                    floorId = "F2",
                    x = 42.0,
                    y = 26.0,
                    nodeId = "node_f2_tata_door_demo",
                    latitude = 39.991556,
                    longitude = 116.339568,
                ),
            ),
        ),
    )

    fun initialState(): ManualIndoorDemoState = ManualIndoorDemoController(defaultScript).state()
}
