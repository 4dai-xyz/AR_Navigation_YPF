package com.aiglasses.poc.glasses

import android.app.Application
import java.io.File
import org.json.JSONObject

data class RecordingAnnotationJsonItem(
    val fileName: String,
    val filePath: String,
    val lastModified: Long,
    val sizeBytes: Long,
    val sourceLabel: String,
    val actionCount: Int,
    val pending: Boolean,
)

class RecordingAnnotationSessionStore(
    private val app: Application,
) {
    private val annotationDir: File
        get() {
            val root = app.getExternalFilesDir(null) ?: app.filesDir
            return File(root, "heycyan_annotations").apply { mkdirs() }
        }

    fun savePending(session: RecordingAnnotationSession): RecordingAnnotationSession {
        val file = File(annotationDir, "${session.sessionId}.pending.navigation.json")
        file.writeText(session.toJsonString())
        return session.copy(jsonLocalPath = file.absolutePath)
    }

    fun listAnnotationJsonFiles(): List<RecordingAnnotationJsonItem> {
        val roots = listOfNotNull(
            app.getExternalFilesDir(null),
            app.filesDir,
        ).distinctBy { it.absolutePath }
        return roots
            .asSequence()
            .flatMap { root ->
                root.walkTopDown()
                    .onEnter { file -> file.isDirectory && file.name !in setOf("cache", "code_cache") }
                    .filter { file -> file.isFile && file.name.endsWith(".navigation.json") }
            }
            .distinctBy { it.absolutePath }
            .map { file -> file.toAnnotationJsonItem() }
            .sortedWith(compareByDescending<RecordingAnnotationJsonItem> { it.lastModified }.thenBy { it.fileName })
            .toList()
    }

    fun bindToNewestVideo(
        session: RecordingAnnotationSession,
        media: List<GlassesMediaItem>,
    ): RecordingAnnotationSession {
        return bindToNewestRecordingVideo(
            session = session,
            media = media.map { it.toRecordingVideoItem() },
        )
    }

    fun bindToNewestRecordingVideo(
        session: RecordingAnnotationSession,
        media: List<RecordingAnnotationVideoItem>,
    ): RecordingAnnotationSession {
        if (session.videoLocalPath != null) return session
        val video = media
            .asSequence()
            .filter { it.mimeType.startsWith("video/") }
            .filterNot { session.existingVideoPaths.contains(it.filePath) }
            .filter { it.lastModified >= session.startedAtWallTimeMs - VIDEO_MATCH_TOLERANCE_MS }
            .maxByOrNull { it.lastModified }
            ?: return session
        val jsonFile = sidecarFileFor(video) ?: return session
        val bound = session.copy(
            videoFileName = video.fileName,
            videoLocalPath = video.filePath,
            jsonLocalPath = jsonFile.absolutePath,
        )
        jsonFile.writeText(bound.toJsonString())
        session.jsonLocalPath
            ?.let(::File)
            ?.takeIf { it.exists() && it.name.endsWith(".pending.navigation.json") }
            ?.delete()
        return bound
    }

    fun resolveAnnotationJsonForVideo(video: GlassesMediaItem): File? {
        val sidecar = sidecarFileFor(video) ?: return null
        if (sidecar.exists()) return sidecar
        val pending = annotationDir
            .listFiles { file -> file.isFile && file.name.endsWith(".pending.navigation.json") }
            ?.maxByOrNull { it.lastModified() }
            ?: return null
        if (!couldMatchVideo(pending, video)) return null
        sidecar.writeText(copyJsonForVideo(pending.readText(), video))
        pending.delete()
        return sidecar
    }

    fun resolveAnnotationJsonForVideo(video: RecordingAnnotationVideoItem): File? {
        val sidecar = sidecarFileFor(video) ?: return null
        if (sidecar.exists()) return sidecar
        val pending = annotationDir
            .listFiles { file -> file.isFile && file.name.endsWith(".pending.navigation.json") }
            ?.maxByOrNull { it.lastModified() }
            ?: return null
        if (!couldMatchVideo(pending, video)) return null
        sidecar.writeText(copyJsonForVideo(pending.readText(), video))
        pending.delete()
        return sidecar
    }

    private fun sidecarFileFor(video: GlassesMediaItem): File? {
        val videoFile = File(video.filePath)
        return File(videoFile.parentFile ?: annotationDir, "${videoFile.nameWithoutExtension}.navigation.json")
    }

    private fun sidecarFileFor(video: RecordingAnnotationVideoItem): File? {
        val videoFile = File(video.filePath)
        return File(videoFile.parentFile ?: annotationDir, "${videoFile.nameWithoutExtension}.navigation.json")
    }

    private fun couldMatchVideo(pending: File, video: GlassesMediaItem): Boolean {
        val startedAt = runCatching {
            JSONObject(pending.readText())
                .optJSONObject("timeline")
                ?.optLong("started_at_wall_time_ms", 0L)
                ?.takeIf { it > 0L }
        }.getOrNull()
        return startedAt == null || video.lastModified >= startedAt - VIDEO_MATCH_TOLERANCE_MS
    }

    private fun couldMatchVideo(pending: File, video: RecordingAnnotationVideoItem): Boolean {
        val startedAt = runCatching {
            JSONObject(pending.readText())
                .optJSONObject("timeline")
                ?.optLong("started_at_wall_time_ms", 0L)
                ?.takeIf { it > 0L }
        }.getOrNull()
        return startedAt == null || video.lastModified >= startedAt - VIDEO_MATCH_TOLERANCE_MS
    }

    private fun copyJsonForVideo(rawJson: String, video: GlassesMediaItem): String {
        return runCatching {
            val root = JSONObject(rawJson)
            val videoJson = root.optJSONObject("video") ?: JSONObject().also { root.put("video", it) }
            videoJson.put("file_name", video.fileName)
            videoJson.put("local_path", video.filePath)
            root.toString(2)
        }.getOrDefault(rawJson)
    }

    private fun copyJsonForVideo(rawJson: String, video: RecordingAnnotationVideoItem): String {
        return runCatching {
            val root = JSONObject(rawJson)
            val videoJson = root.optJSONObject("video") ?: JSONObject().also { root.put("video", it) }
            videoJson.put("file_name", video.fileName)
            videoJson.put("local_path", video.filePath)
            root.toString(2)
        }.getOrDefault(rawJson)
    }

    companion object {
        private const val VIDEO_MATCH_TOLERANCE_MS = 5_000L
    }

    private fun File.toAnnotationJsonItem(): RecordingAnnotationJsonItem {
        val parsed = runCatching { JSONObject(readText()) }.getOrNull()
        val source = parsed
            ?.optJSONObject("device")
            ?.optString("source_label")
            ?.takeIf { it.isNotBlank() }
            ?: parsed?.optString("source")?.takeIf { it.isNotBlank() }
            ?: "未知来源"
        val actionCount = parsed?.optJSONArray("actions")?.length() ?: 0
        return RecordingAnnotationJsonItem(
            fileName = name,
            filePath = absolutePath,
            lastModified = lastModified().takeIf { it > 0L } ?: System.currentTimeMillis(),
            sizeBytes = length(),
            sourceLabel = source,
            actionCount = actionCount,
            pending = name.endsWith(".pending.navigation.json"),
        )
    }
}

private fun GlassesMediaItem.toRecordingVideoItem(): RecordingAnnotationVideoItem {
    return RecordingAnnotationVideoItem(
        fileName = fileName,
        filePath = filePath,
        mimeType = mimeType,
        lastModified = lastModified,
    )
}
