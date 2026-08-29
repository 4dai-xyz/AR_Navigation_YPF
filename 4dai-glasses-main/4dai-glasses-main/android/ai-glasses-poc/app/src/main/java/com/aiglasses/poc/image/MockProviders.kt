package com.aiglasses.poc.image

import kotlinx.coroutines.delay

class MockAlbumSyncProvider : ImageProvider {
    override val id: String = "glasses_album_sync"
    override val displayName: String = "眼镜相册同步 Mock"

    override suspend fun capture(candidateFloorId: String?): CapturedFrame {
        delay(120)
        return CapturedFrame(
            providerId = id,
            bytes = "placeholder_jpg_kf_0001\n".encodeToByteArray(),
            fileName = "mock_album_sync.jpg",
            candidateFloorId = candidateFloorId,
            width = 1280,
            height = 720,
        )
    }
}

class MockThumbnailProvider : ImageProvider {
    override val id: String = "glasses_thumbnail"
    override val displayName: String = "眼镜缩略图 Mock"

    override suspend fun capture(candidateFloorId: String?): CapturedFrame {
        delay(80)
        return CapturedFrame(
            providerId = id,
            bytes = "x".encodeToByteArray(),
            fileName = "mock_thumbnail.jpg",
            candidateFloorId = candidateFloorId,
            width = 320,
            height = 180,
        )
    }
}

class MockPrivateStreamProvider : ImageProvider {
    override val id: String = "glasses_private_stream"
    override val displayName: String = "眼镜私有流 Mock"

    override suspend fun capture(candidateFloorId: String?): CapturedFrame {
        delay(60)
        return CapturedFrame(
            providerId = id,
            bytes = "placeholder_jpg_kf_0002_noise".encodeToByteArray(),
            fileName = "mock_private_stream.jpg",
            candidateFloorId = candidateFloorId,
            width = 1280,
            height = 720,
        )
    }
}

class MockPhoneFallbackProvider : ImageProvider {
    override val id: String = "phone_camera_fallback"
    override val displayName: String = "手机摄像头兜底"

    override suspend fun capture(candidateFloorId: String?): CapturedFrame {
        delay(100)
        return CapturedFrame(
            providerId = id,
            bytes = "placeholder_jpg_kf_0002\n".encodeToByteArray(),
            fileName = "mock_phone_fallback.jpg",
            candidateFloorId = candidateFloorId,
            width = 1280,
            height = 720,
        )
    }
}
