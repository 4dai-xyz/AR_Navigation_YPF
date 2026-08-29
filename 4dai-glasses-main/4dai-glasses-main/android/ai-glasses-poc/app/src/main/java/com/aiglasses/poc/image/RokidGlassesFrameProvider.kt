package com.aiglasses.poc.image

import com.aiglasses.poc.rokid.RokidRuntimeBridge
import kotlinx.coroutines.delay

class RokidGlassesFrameProvider : ImageProvider {
    override val id: String = "rokid_glasses_frame"
    override val displayName: String = "Rokid HTTP图传"

    override suspend fun capture(candidateFloorId: String?): CapturedFrame {
        delay(20)
        return RokidRuntimeBridge.latestCapturedFrame(candidateFloorId)
    }
}
