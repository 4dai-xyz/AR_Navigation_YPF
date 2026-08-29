package com.aiglasses.poc.image

import com.aiglasses.poc.rokid.RokidImuSample

data class CapturedFrame(
    val providerId: String,
    val bytes: ByteArray,
    val fileName: String,
    val mimeType: String = "image/jpeg",
    val candidateFloorId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val captureId: String? = null,
    val captureTimestampMs: Long? = null,
    val captureMode: String? = null,
    val imuAtCapture: RokidImuSample? = null,
)

interface ImageProvider {
    val id: String
    val displayName: String
    suspend fun capture(candidateFloorId: String?): CapturedFrame
}
