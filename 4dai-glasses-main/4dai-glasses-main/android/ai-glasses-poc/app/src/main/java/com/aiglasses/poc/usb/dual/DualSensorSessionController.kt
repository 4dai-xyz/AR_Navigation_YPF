package com.aiglasses.poc.usb.dual

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

data class DualRecordingConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val cameraTargetFps: Int = 30,
    val imuTargetHz: Int = 200,
    val videoBitrateMbps: Float = 8f,
    val iFrameIntervalSeconds: Int = 1,
)

data class CameraSessionDeviceInfo(
    val role: String,
    val fingerprint: String,
    val label: String,
    val width: Int,
    val height: Int,
    val targetFps: Int,
    val format: String = "MJPEG",
)

data class ImuSessionDeviceInfo(
    val fingerprint: String,
    val label: String,
    val targetHz: Int,
)

data class CameraFrameRecord(
    val frameIndex: Long,
    val frameElapsedNs: Long,
    val arrivalElapsedNs: Long,
    val presentationTimeUs: Long?,
    val width: Int,
    val height: Int,
    val format: String,
    val observedFpsWindow: Double,
    val estimated: Boolean,
)

data class CameraPairStats(
    val count: Int,
    val minMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val maxMs: Double,
    val badCount: Int,
)

class DualSensorSessionController(
    private val context: Context,
) {
    private val lock = Any()
    private var active: ActiveSession? = null
    var lastCompletedSessionDir: File? = null
        private set

    val isRecording: Boolean
        get() = synchronized(lock) { active != null }

    fun start(
        config: DualRecordingConfig,
        left: CameraSessionDeviceInfo,
        right: CameraSessionDeviceInfo,
        imu: ImuSessionDeviceInfo?,
    ): ActiveSession {
        synchronized(lock) {
            check(active == null) { "session already active" }
            val session = ActiveSession(context, config, left, right, imu)
            session.start()
            active = session
            return session
        }
    }

    fun current(): ActiveSession? = synchronized(lock) { active }

    fun stop(status: String = "complete"): ActiveSession? {
        synchronized(lock) {
            val session = active ?: return null
            session.markStopping(status)
            return session
        }
    }

    fun finalizeIfReady(force: Boolean = false): File? {
        synchronized(lock) {
            val session = active ?: return null
            if (!force && !session.canFinalize()) return null
            session.finalizeSession()
            active = null
            lastCompletedSessionDir = session.sessionDir
            return session.sessionDir
        }
    }

    fun exportLastSessionZip(): File? {
        val dir = lastCompletedSessionDir ?: return null
        return exportSessionZip(dir)
    }

    class ActiveSession(
        private val context: Context,
        val config: DualRecordingConfig,
        private val left: CameraSessionDeviceInfo,
        private val right: CameraSessionDeviceInfo,
        private val imu: ImuSessionDeviceInfo?,
    ) {
        val sessionId: String = timestampFormatter().format(Date()) + "_dual_uvc_imu"
        val sessionDir: File = File(sessionRoot(context), sessionId)
        val leftVideoFile = File(sessionDir, "left.mp4")
        val rightVideoFile = File(sessionDir, "right.mp4")
        val imuCsvFile = File(sessionDir, "imu.csv")
        val leftFramesFile = File(sessionDir, "camera_frames_left.csv")
        val rightFramesFile = File(sessionDir, "camera_frames_right.csv")
        val pairsFile = File(sessionDir, "camera_pairs.csv")
        val eventsFile = File(sessionDir, "events.jsonl")
        val syncReportFile = File(sessionDir, "sync_report.json")
        val sessionJsonFile = File(sessionDir, "session.json")
        val readmeFile = File(sessionDir, "README.txt")
        val startRequestedNs: Long = SystemClock.elapsedRealtimeNanos()
        var t0Ns: Long = startRequestedNs
            private set
        var stopRequestedNs: Long? = null
            private set
        private var status: String = "recording"
        private var finalized = false
        private var leftVideoDone = false
        private var rightVideoDone = false
        private val leftFrames = mutableListOf<CameraFrameRecord>()
        private val rightFrames = mutableListOf<CameraFrameRecord>()
        private lateinit var leftFramesWriter: BufferedWriter
        private lateinit var rightFramesWriter: BufferedWriter
        private lateinit var eventsWriter: BufferedWriter

        val durationNs: Long
            get() = (stopRequestedNs ?: SystemClock.elapsedRealtimeNanos()) - t0Ns

        fun start() {
            sessionDir.mkdirs()
            leftFramesWriter = BufferedWriter(FileWriter(leftFramesFile))
            rightFramesWriter = BufferedWriter(FileWriter(rightFramesFile))
            eventsWriter = BufferedWriter(FileWriter(eventsFile))
            leftFramesWriter.write(CAMERA_FRAME_HEADER)
            rightFramesWriter.write(CAMERA_FRAME_HEADER)
            imuCsvFile.writeText(IMU_CSV_HEADER)
            writeReadme()
            event("recording_start_requested", mapOf("session_id" to sessionId))
        }

        fun recordCameraFrame(role: String, record: CameraFrameRecord) {
            val normalized = record.copy(frameElapsedNs = record.frameElapsedNs, arrivalElapsedNs = record.arrivalElapsedNs)
            val writer = if (role == "left") leftFramesWriter else rightFramesWriter
            val list = if (role == "left") leftFrames else rightFrames
            synchronized(writer) {
                writer.write(normalized.toCsvLine(t0Ns))
                writer.flush()
            }
            synchronized(list) {
                list += normalized
            }
        }

        fun imuCsvAppend(line: String) {
            synchronized(imuCsvFile) {
                imuCsvFile.appendText(line)
            }
        }

        fun markVideoStarted(role: String) {
            event("${role}_recording_started")
        }

        fun markVideoDone(role: String, ok: Boolean, message: String = "") {
            if (!ok && status == "complete") status = "incomplete"
            if (role == "left") leftVideoDone = true else rightVideoDone = true
            event("${role}_recording_done", mapOf("ok" to ok.toString(), "message" to message))
        }

        fun markStopping(nextStatus: String) {
            if (stopRequestedNs == null) {
                stopRequestedNs = SystemClock.elapsedRealtimeNanos()
                status = nextStatus
                event("recording_stop_requested", mapOf("status" to nextStatus))
            }
        }

        fun markIncomplete(reason: String) {
            status = "incomplete"
            event("session_incomplete", mapOf("reason" to reason))
        }

        fun canFinalize(): Boolean = stopRequestedNs != null && leftVideoDone && rightVideoDone

        fun finalizeSession() {
            if (finalized) return
            finalized = true
            if (!leftVideoDone || !rightVideoDone) status = "incomplete"
            generateEstimatedFramesIfNeeded("left")
            generateEstimatedFramesIfNeeded("right")
            val stats = writeCameraPairsAndReport()
            writeSessionJson(stats)
            event("session_finalized", mapOf("status" to status))
            closeQuietly(leftFramesWriter)
            closeQuietly(rightFramesWriter)
            closeQuietly(eventsWriter)
        }

        fun storageFreeBytes(): Long {
            return runCatching {
                val stat = StatFs(sessionDir.absolutePath)
                stat.availableBytes
            }.getOrDefault(0L)
        }

        fun event(name: String, values: Map<String, String> = emptyMap()) {
            val json = JSONObject()
                .put("event", name)
                .put("elapsed_ns", SystemClock.elapsedRealtimeNanos())
                .put("relative_ns", SystemClock.elapsedRealtimeNanos() - t0Ns)
            values.forEach { (key, value) -> json.put(key, value) }
            synchronized(eventsWriter) {
                eventsWriter.write(json.toString())
                eventsWriter.write("\n")
                eventsWriter.flush()
            }
        }

        private fun generateEstimatedFramesIfNeeded(role: String) {
            val list = if (role == "left") leftFrames else rightFrames
            synchronized(list) {
                if (list.isNotEmpty()) return
            }
            val framePeriodNs = 1_000_000_000L / config.cameraTargetFps.coerceAtLeast(1)
            val start = t0Ns
            val end = stopRequestedNs ?: SystemClock.elapsedRealtimeNanos()
            var timestamp = start
            var index = 1L
            while (timestamp <= end) {
                recordCameraFrame(
                    role,
                    CameraFrameRecord(
                        frameIndex = index,
                        frameElapsedNs = timestamp,
                        arrivalElapsedNs = timestamp,
                        presentationTimeUs = (timestamp - start) / 1000L,
                        width = config.width,
                        height = config.height,
                        format = "estimated",
                        observedFpsWindow = config.cameraTargetFps.toDouble(),
                        estimated = true,
                    ),
                )
                timestamp += framePeriodNs
                index += 1
            }
        }

        private fun writeCameraPairsAndReport(): CameraPairStats {
            val leftSnapshot = synchronized(leftFrames) { leftFrames.toList() }
            val rightSnapshot = synchronized(rightFrames) { rightFrames.toList() }
            val deltas = mutableListOf<Double>()
            BufferedWriter(FileWriter(pairsFile)).use { writer ->
                writer.write("pair_index,left_frame_index,right_frame_index,left_elapsed_ns,right_elapsed_ns,delta_ms,quality\n")
                var rightIndex = 0
                leftSnapshot.forEachIndexed { pairIndex, leftFrame ->
                    while (
                        rightIndex + 1 < rightSnapshot.size &&
                        abs(rightSnapshot[rightIndex + 1].frameElapsedNs - leftFrame.frameElapsedNs) <
                        abs(rightSnapshot[rightIndex].frameElapsedNs - leftFrame.frameElapsedNs)
                    ) {
                        rightIndex += 1
                    }
                    val rightFrame = rightSnapshot.getOrNull(rightIndex) ?: return@forEachIndexed
                    val deltaMs = abs(leftFrame.frameElapsedNs - rightFrame.frameElapsedNs) / 1_000_000.0
                    deltas += deltaMs
                    writer.write(
                        "${pairIndex + 1},${leftFrame.frameIndex},${rightFrame.frameIndex},${leftFrame.frameElapsedNs},${rightFrame.frameElapsedNs},${"%.3f".format(Locale.US, deltaMs)},${deltaQuality(deltaMs)}\n",
                    )
                }
            }
            val sorted = deltas.sorted()
            val stats = CameraPairStats(
                count = sorted.size,
                minMs = sorted.firstOrNull() ?: 0.0,
                p50Ms = percentile(sorted, 0.50),
                p95Ms = percentile(sorted, 0.95),
                maxMs = sorted.lastOrNull() ?: 0.0,
                badCount = sorted.count { it > 20.0 },
            )
            syncReportFile.writeText(
                JSONObject()
                    .put("schema_version", "visionroute.dual_uvc_imu_sync_report.v0.1")
                    .put("timebase", "android_elapsed_realtime_nanos")
                    .put("camera_pair_count", stats.count)
                    .put("camera_pair_delta_ms_min", stats.minMs)
                    .put("camera_pair_delta_ms_p50", stats.p50Ms)
                    .put("camera_pair_delta_ms_p95", stats.p95Ms)
                    .put("camera_pair_delta_ms_max", stats.maxMs)
                    .put("camera_pair_bad_count", stats.badCount)
                    .put("alignment_note", "software timestamps only; no hardware sync claimed")
                    .toString(2),
            )
            return stats
        }

        private fun writeSessionJson(stats: CameraPairStats) {
            val files = JSONArray()
            listOf(
                "left.mp4" to "video",
                "right.mp4" to "video",
                "imu.csv" to "imu_csv",
                "camera_frames_left.csv" to "camera_frames_csv",
                "camera_frames_right.csv" to "camera_frames_csv",
                "camera_pairs.csv" to "camera_pairs_csv",
                "events.jsonl" to "events",
                "sync_report.json" to "sync_report",
                "README.txt" to "readme",
            ).forEach { (path, type) ->
                val file = File(sessionDir, path)
                files.put(JSONObject().put("path", path).put("type", type).put("bytes", if (file.exists()) file.length() else 0L))
            }
            val json = JSONObject()
                .put("schema_version", "visionroute.dual_uvc_imu_session.v0.1")
                .put("session_id", sessionId)
                .put("status", status)
                .put("timebase", "android_elapsed_realtime_nanos")
                .put("t0_ns", t0Ns)
                .put("recording_start_requested_ns", startRequestedNs)
                .put("recording_stop_requested_ns", stopRequestedNs ?: JSONObject.NULL)
                .put("duration_ms", durationNs / 1_000_000L)
                .put(
                    "devices",
                    JSONObject()
                        .put("left_camera", left.toJson("left.mp4", "camera_frames_left.csv"))
                        .put("right_camera", right.toJson("right.mp4", "camera_frames_right.csv"))
                        .put("imu", imu?.toJson("imu.csv") ?: JSONObject.NULL),
                )
                .put("config", JSONObject().put("width", config.width).put("height", config.height).put("camera_target_fps", config.cameraTargetFps).put("imu_target_hz", config.imuTargetHz))
                .put("files", files)
                .put("sync", JSONObject().put("camera_pair_delta_ms_p50", stats.p50Ms).put("camera_pair_delta_ms_p95", stats.p95Ms).put("camera_pair_bad_count", stats.badCount))
            sessionJsonFile.writeText(json.toString(2))
        }

        private fun writeReadme() {
            readmeFile.writeText(
                """
                VisionRoute dual UVC + USB IMU session

                Timebase: android_elapsed_realtime_nanos.
                All CSV timestamps for alignment use SystemClock.elapsedRealtimeNanos().
                This dataset is software aligned only; it does not claim hardware synchronization.

                Files:
                - left.mp4 / right.mp4: independent UVC recordings.
                - imu.csv: WT901SDCL-BT50 USB CDC samples. Batched samples are backfilled by target IMU period.
                - camera_frames_left.csv / camera_frames_right.csv: camera preview arrival timestamps, or estimated timestamps if callbacks are unavailable.
                - camera_pairs.csv / sync_report.json: nearest-neighbor left/right frame pairing report.
                """.trimIndent(),
            )
        }
    }
}

private const val CAMERA_FRAME_HEADER =
    "frame_index,frame_elapsed_ns,relative_ms,arrival_elapsed_ns,presentation_time_us,width,height,format,observed_fps_window,estimated\n"

const val IMU_CSV_HEADER =
    "sample_index,sample_elapsed_ns,relative_ms,read_end_elapsed_ns,batch_size,acc_x_g,acc_y_g,acc_z_g,gyro_x_dps,gyro_y_dps,gyro_z_dps,roll_deg,pitch_deg,yaw_deg,raw_hex\n"

private fun CameraFrameRecord.toCsvLine(t0Ns: Long): String {
    val relativeMs = (frameElapsedNs - t0Ns) / 1_000_000.0
    return listOf(
        frameIndex,
        frameElapsedNs,
        "%.3f".format(Locale.US, relativeMs),
        arrivalElapsedNs,
        presentationTimeUs ?: "",
        width,
        height,
        format,
        "%.2f".format(Locale.US, observedFpsWindow),
        estimated,
    ).joinToString(",") + "\n"
}

private fun CameraSessionDeviceInfo.toJson(file: String, frameCsv: String): JSONObject {
    return JSONObject()
        .put("role", role)
        .put("device_fingerprint", fingerprint)
        .put("label", label)
        .put("width", width)
        .put("height", height)
        .put("format", format)
        .put("target_fps", targetFps)
        .put("file", file)
        .put("frame_csv", frameCsv)
}

private fun ImuSessionDeviceInfo.toJson(file: String): JSONObject {
    return JSONObject()
        .put("model", "WT901SDCL-BT50")
        .put("transport", "usb_cdc_acm")
        .put("device_fingerprint", fingerprint)
        .put("label", label)
        .put("target_hz", targetHz)
        .put("file", file)
}

private fun deltaQuality(deltaMs: Double): String = when {
    deltaMs <= 10.0 -> "good"
    deltaMs <= 20.0 -> "usable"
    else -> "bad"
}

private fun percentile(sorted: List<Double>, ratio: Double): Double {
    if (sorted.isEmpty()) return 0.0
    val index = ((sorted.size - 1) * ratio).toInt().coerceIn(sorted.indices)
    return sorted[index]
}

fun sessionRoot(context: Context): File {
    val movies = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
    return File(movies, "VisionRoute/sessions").apply { mkdirs() }
}

fun exportSessionZip(sessionDir: File): File {
    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val outputDir = File(downloads, "VisionRoute/sessions").apply { mkdirs() }
    val output = File(outputDir, "${sessionDir.name}.zip")
    ZipOutputStream(output.outputStream()).use { zip ->
        sessionDir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val entryName = file.relativeTo(sessionDir).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(entryName))
                FileInputStream(file).use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
    }
    return output
}

private fun closeQuietly(writer: BufferedWriter?) {
    runCatching {
        writer?.flush()
        writer?.close()
    }
}

private fun timestampFormatter(): SimpleDateFormat {
    return SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }
}
