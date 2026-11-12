package dev.mahali.hatch

import android.app.ActivityManager
import android.content.Context
import dev.mahali.hatch.core.DeviceClass

internal fun Context.deviceClass(): DeviceClass {
    val am = getSystemService(ActivityManager::class.java)
    return DeviceClass.of(am.isLowRamDevice, am.memoryClass, Runtime.getRuntime().availableProcessors())
}
