package com.aiglasses.poc

import com.aiglasses.poc.outdoor.ExternalLocationIntentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ExternalLocationIntentParserTest {
    @Test
    fun parsesGeoUriWithQueryCoordinate() {
        val payload = ExternalLocationIntentParser.parse(
            action = "android.intent.action.VIEW",
            dataString = "geo:0,0?q=39.991583,116.338965(Wudaokou West Gate)",
            mimeType = null,
            sharedText = null,
        )

        assertNotNull(payload)
        assertEquals("geo 位置", payload!!.sourceLabel)
        assertEquals("Wudaokou West Gate", payload.title)
        val point = payload.point!!
        assertEquals(39.991583, point.latitude, 0.000001)
        assertEquals(116.338965, point.longitude, 0.000001)
    }

    @Test
    fun parsesAmapMarkerLngLatPosition() {
        val payload = ExternalLocationIntentParser.parse(
            action = "android.intent.action.VIEW",
            dataString = "https://uri.amap.com/marker?position=116.338965,39.991583&name=Wudaokou",
            mimeType = null,
            sharedText = null,
        )

        assertNotNull(payload)
        assertEquals("高德地图链接", payload!!.sourceLabel)
        assertEquals("Wudaokou", payload.title)
        val point = payload.point!!
        assertEquals(39.991583, point.latitude, 0.000001)
        assertEquals(116.338965, point.longitude, 0.000001)
    }

    @Test
    fun parsesTencentMarkerLatLngCoordinate() {
        val payload = ExternalLocationIntentParser.parse(
            action = "android.intent.action.VIEW",
            dataString = "https://apis.map.qq.com/uri/v1/marker?marker=coord:39.991583,116.338965;title:Wudaokou",
            mimeType = null,
            sharedText = null,
        )

        assertNotNull(payload)
        assertEquals("腾讯地图链接", payload!!.sourceLabel)
        assertEquals("Wudaokou", payload.title)
        val point = payload.point!!
        assertEquals(39.991583, point.latitude, 0.000001)
        assertEquals(116.338965, point.longitude, 0.000001)
    }

    @Test
    fun parsesTencentRoutePlanToCoordinate() {
        val payload = ExternalLocationIntentParser.parse(
            action = "android.intent.action.VIEW",
            dataString = "qqmap://map/routeplan?type=walk&to=Wudaokou&tocoord=39.991583,116.338965",
            mimeType = null,
            sharedText = null,
        )

        assertNotNull(payload)
        assertEquals("腾讯地图链接", payload!!.sourceLabel)
        assertEquals("Wudaokou", payload.title)
        val point = payload.point!!
        assertEquals(39.991583, point.latitude, 0.000001)
        assertEquals(116.338965, point.longitude, 0.000001)
    }

    @Test
    fun parsesSharedWechatStyleTextWithCoordinates() {
        val payload = ExternalLocationIntentParser.parse(
            action = "android.intent.action.SEND",
            dataString = null,
            mimeType = "text/plain",
            sharedText = "Wudaokou Shopping Center\n39.991583,116.338965",
        )

        assertNotNull(payload)
        assertEquals("外部分享", payload!!.sourceLabel)
        assertEquals("Wudaokou Shopping Center", payload.title)
        val point = payload.point!!
        assertEquals(39.991583, point.latitude, 0.000001)
        assertEquals(116.338965, point.longitude, 0.000001)
    }

    @Test
    fun parsesSharedTextAsKeywordWhenNoCoordinateExists() {
        val payload = ExternalLocationIntentParser.parse(
            action = "android.intent.action.SEND",
            dataString = null,
            mimeType = "text/plain",
            sharedText = "Wudaokou Shopping Center Nike",
        )

        assertNotNull(payload)
        assertEquals("外部分享", payload!!.sourceLabel)
        assertEquals("Wudaokou Shopping Center Nike", payload.keyword)
        assertEquals(null, payload.point)
    }
}
