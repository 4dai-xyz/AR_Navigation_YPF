package com.aiglasses.poc.glasses

object GlassesMediaClassifier {
    fun mimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "aac" -> "audio/aac"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "pcm" -> "audio/pcm"
            else -> "application/octet-stream"
        }
    }

    fun isPreviewImage(fileName: String): Boolean = mimeType(fileName).startsWith("image/")

    fun isPreviewVideo(fileName: String): Boolean = mimeType(fileName).startsWith("video/")

    fun isSupportedMedia(fileName: String): Boolean {
        val mime = mimeType(fileName)
        return mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")
    }
}
