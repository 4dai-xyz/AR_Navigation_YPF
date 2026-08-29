package com.aiglasses.poc.glasses

enum class RecordingAnnotationAction(val jsonValue: String, val label: String) {
    FORWARD("FORWARD", "前进"),
    TURN_LEFT("TURN_LEFT", "向左转"),
    TURN_RIGHT("TURN_RIGHT", "向右转"),
    FLOOR_UP("FLOOR_UP", "上楼"),
    FLOOR_DOWN("FLOOR_DOWN", "下楼"),
}

enum class RecordingAnnotationDeviceSource(val jsonValue: String, val label: String) {
    AI_GLASSES("ai_glasses", "AI 眼镜"),
    USB_CAMERA("usb_camera", "USB 相机"),
}

data class RecordingAnnotationVideoItem(
    val fileName: String,
    val filePath: String,
    val mimeType: String,
    val lastModified: Long,
)

data class RecordingAnnotationEvent(
    val id: Int,
    val action: RecordingAnnotationAction,
    val elapsedMs: Long,
    val createdAtWallTimeMs: Long,
)

data class RecordingAnnotationSession(
    val sessionId: String,
    val deviceSource: RecordingAnnotationDeviceSource = RecordingAnnotationDeviceSource.AI_GLASSES,
    val deviceName: String?,
    val deviceAddress: String?,
    val startedAtWallTimeMs: Long,
    val startedAtElapsedMs: Long,
    val existingVideoPaths: Set<String>,
    val recordingConfirmedElapsedMs: Long? = null,
    val manualSyncElapsedMs: Long? = null,
    val stoppedAtElapsedMs: Long? = null,
    val events: List<RecordingAnnotationEvent> = emptyList(),
    val videoFileName: String? = null,
    val videoLocalPath: String? = null,
    val jsonLocalPath: String? = null,
) {
    val active: Boolean
        get() = stoppedAtElapsedMs == null

    val aligned: Boolean
        get() = alignmentElapsedMs != null

    val alignmentElapsedMs: Long?
        get() = manualSyncElapsedMs ?: recordingConfirmedElapsedMs

    val timeAlignment: String
        get() = when {
            manualSyncElapsedMs != null -> "manual_walk_start_marker"
            recordingConfirmedElapsedMs != null -> "sdk_start_video_response"
            else -> "not_aligned"
        }

    val durationMs: Long
        get() = stoppedAtElapsedMs?.minus(alignmentElapsedMs ?: startedAtElapsedMs) ?: 0L

    fun toJsonString(): String {
        return buildString {
            appendLine("{")
            appendLine("  \"schema_version\": 1,")
            appendLine("  \"session_id\": \"${sessionId.escapeJson()}\",")
            appendLine("  \"capture_mode\": \"manual_navigation_annotation\",")
            appendLine("  \"source\": \"${deviceSource.jsonValue.escapeJson()}\",")
            appendLine("  \"time_alignment\": \"${timeAlignment.escapeJson()}\",")
            appendLine("  \"device\": {")
            appendLine("    \"source\": \"${deviceSource.jsonValue.escapeJson()}\",")
            appendLine("    \"source_label\": \"${deviceSource.label.escapeJson()}\",")
            appendLine("    \"name\": ${deviceName.jsonStringOrNull()},")
            appendLine("    \"address\": ${deviceAddress.jsonStringOrNull()}")
            appendLine("  },")
            appendLine("  \"video\": {")
            appendLine("    \"file_name\": ${videoFileName.jsonStringOrNull()},")
            appendLine("    \"local_path\": ${videoLocalPath.jsonStringOrNull()},")
            appendLine("    \"duration_ms\": ${durationMs.coerceAtLeast(0L)}")
            appendLine("  },")
            appendLine("  \"timeline\": {")
            appendLine("    \"app_record_command_elapsed_ms\": 0,")
            appendLine("    \"started_at_wall_time_ms\": $startedAtWallTimeMs,")
            appendLine("    \"recording_confirmed_elapsed_ms\": ${recordingConfirmedElapsedMs.relativeToStart()},")
            appendLine("    \"manual_sync_elapsed_ms\": ${manualSyncElapsedMs.relativeToStart()},")
            appendLine("    \"alignment_elapsed_ms\": ${alignmentElapsedMs.relativeToStart()},")
            appendLine("    \"stopped_at_elapsed_ms\": ${stoppedAtElapsedMs.relativeToStart()}")
            appendLine("  },")
            appendLine("  \"actions\": [")
            events.forEachIndexed { index, event ->
                appendLine("    {")
                appendLine("      \"id\": ${event.id},")
                appendLine("      \"type\": \"${event.action.jsonValue}\",")
                appendLine("      \"label\": \"${event.action.label.escapeJson()}\",")
                appendLine("      \"elapsed_ms\": ${event.elapsedMs},")
                appendLine("      \"created_at_wall_time_ms\": ${event.createdAtWallTimeMs}")
                append("    }")
                if (index != events.lastIndex) append(",")
                appendLine()
            }
            appendLine("  ]")
            appendLine("}")
        }
    }

    private fun Long?.relativeToStart(): String {
        return this?.minus(startedAtElapsedMs)?.coerceAtLeast(0L)?.toString() ?: "null"
    }
}

private fun String?.jsonStringOrNull(): String {
    return this?.let { "\"${it.escapeJson()}\"" } ?: "null"
}

private fun String.escapeJson(): String {
    return buildString {
        this@escapeJson.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}
