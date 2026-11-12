package dev.mahali.hatch.feature.terminal

import dev.mahali.hatch.core.DeviceClass
import dev.mahali.hatch.renderer.TerminalRendering

// Call once at startup, before any pane draws.
public fun useDeviceClass(deviceClass: DeviceClass) {
    if (deviceClass == DeviceClass.Low) {
        TerminalRendering.lowEnd()
        previewLimits = PreviewLimits.Low
    }
}

/** How much memory a file preview may use. A low-end phone has a 128–192 MB heap and few video decoders. */
internal class PreviewLimits(
    val imageBytes: Long,
    val textBytes: Int,
    val videoBufferBytes: Int,
    val chunkBytes: Int,
    // Decode images with less memory (RGB_565 where it applies).
    val lowRam: Boolean,
    val pdfPagePixels: Int,
) {
    companion object {
        val Normal = PreviewLimits(50L shl 20, 1 shl 20, 32 shl 20, 256 shl 10, lowRam = false, pdfPagePixels = 4_000_000)
        val Low = PreviewLimits(15L shl 20, 256 shl 10, 12 shl 20, 128 shl 10, lowRam = true, pdfPagePixels = 1_000_000)
    }
}

internal var previewLimits: PreviewLimits = PreviewLimits.Normal
    private set
