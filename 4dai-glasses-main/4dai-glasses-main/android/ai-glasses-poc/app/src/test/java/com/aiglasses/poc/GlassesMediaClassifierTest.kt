package com.aiglasses.poc

import com.aiglasses.poc.glasses.GlassesMediaClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesMediaClassifierTest {
    @Test
    fun imageExtensionsUseImageMimeTypes() {
        assertEquals("image/jpeg", GlassesMediaClassifier.mimeType("photo.JPG"))
        assertEquals("image/png", GlassesMediaClassifier.mimeType("thumbnail.png"))
        assertTrue(GlassesMediaClassifier.isPreviewImage("photo.jpeg"))
    }

    @Test
    fun videoExtensionsUseVideoMimeTypes() {
        assertEquals("video/mp4", GlassesMediaClassifier.mimeType("clip.mp4"))
        assertTrue(GlassesMediaClassifier.isPreviewVideo("clip.mov"))
    }

    @Test
    fun unsupportedFilesAreNotListedAsMedia() {
        assertEquals("application/octet-stream", GlassesMediaClassifier.mimeType("config.txt"))
        assertFalse(GlassesMediaClassifier.isSupportedMedia("config.txt"))
    }
}
