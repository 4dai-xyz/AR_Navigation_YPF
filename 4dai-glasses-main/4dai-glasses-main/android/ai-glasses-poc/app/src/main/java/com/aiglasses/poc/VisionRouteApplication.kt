package com.aiglasses.poc

import android.app.Application
import com.aiglasses.poc.glasses.HeyCyanGlassesManager
import com.aiglasses.poc.usb.UsbCameraRecordingManager
import com.rokid.cxr.link.CXRLink

class VisionRouteApplication : Application() {
    var sharedRokidCxrLink: CXRLink? = null

    override fun onCreate() {
        super.onCreate()
        HeyCyanGlassesManager.initialize(this)
        UsbCameraRecordingManager.initialize(this)
    }
}
