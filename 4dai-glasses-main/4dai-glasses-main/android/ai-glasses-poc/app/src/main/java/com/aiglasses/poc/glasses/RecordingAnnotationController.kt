package com.aiglasses.poc.glasses

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingAnnotationController(
    private val elapsedTimeMs: () -> Long,
    private val wallTimeMs: () -> Long,
) {
    private val sessionFormatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private var currentSession: RecordingAnnotationSession? = null

    val session: RecordingAnnotationSession?
        get() = currentSession

    fun start(
        device: GlassesDevice?,
        existingVideoPaths: Set<String>,
    ): RecordingAnnotationSession {
        return start(
            source = RecordingAnnotationDeviceSource.AI_GLASSES,
            deviceName = device?.name,
            deviceAddress = device?.address,
            existingVideoPaths = existingVideoPaths,
        )
    }

    fun start(
        source: RecordingAnnotationDeviceSource,
        deviceName: String?,
        deviceAddress: String?,
        existingVideoPaths: Set<String>,
    ): RecordingAnnotationSession {
        val wallTime = wallTimeMs()
        val session = RecordingAnnotationSession(
            sessionId = "vr_${sessionFormatter.format(Date(wallTime))}",
            deviceSource = source,
            deviceName = deviceName,
            deviceAddress = deviceAddress,
            startedAtWallTimeMs = wallTime,
            startedAtElapsedMs = elapsedTimeMs(),
            existingVideoPaths = existingVideoPaths,
        )
        currentSession = session
        return session
    }

    fun record(action: RecordingAnnotationAction): RecordingAnnotationSession? {
        val session = currentSession?.takeIf { it.active } ?: return currentSession
        val alignmentElapsedMs = session.alignmentElapsedMs ?: return currentSession
        val event = RecordingAnnotationEvent(
            id = session.events.size + 1,
            action = action,
            elapsedMs = (elapsedTimeMs() - alignmentElapsedMs).coerceAtLeast(0L),
            createdAtWallTimeMs = wallTimeMs(),
        )
        currentSession = session.copy(events = session.events + event)
        return currentSession
    }

    fun confirmRecordingStarted(): RecordingAnnotationSession? {
        val session = currentSession?.takeIf { it.active } ?: return currentSession
        if (session.recordingConfirmedElapsedMs != null) return session
        currentSession = session.copy(recordingConfirmedElapsedMs = elapsedTimeMs())
        return currentSession
    }

    fun markManualSync(): RecordingAnnotationSession? {
        val session = currentSession?.takeIf { it.active } ?: return currentSession
        currentSession = session.copy(
            manualSyncElapsedMs = elapsedTimeMs(),
            events = emptyList(),
        )
        return currentSession
    }

    fun undoLast(): RecordingAnnotationSession? {
        val session = currentSession?.takeIf { it.active } ?: return currentSession
        if (session.events.isEmpty()) return session
        currentSession = session.copy(events = session.events.dropLast(1))
        return currentSession
    }

    fun stop(): RecordingAnnotationSession? {
        val session = currentSession?.takeIf { it.active } ?: return currentSession
        currentSession = session.copy(stoppedAtElapsedMs = elapsedTimeMs())
        return currentSession
    }

    fun updateSession(session: RecordingAnnotationSession) {
        currentSession = session
    }

    fun discard() {
        currentSession = null
    }
}
