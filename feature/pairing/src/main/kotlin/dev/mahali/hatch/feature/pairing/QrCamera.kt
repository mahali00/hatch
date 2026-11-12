package dev.mahali.hatch.feature.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

@Composable
internal fun rememberCameraPermission(): Boolean? {
    val context = LocalContext.current
    fun allowed() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(if (allowed()) true else null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (granted == null) launcher.launch(Manifest.permission.CAMERA) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME && allowed()) granted = true }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return granted
}

/** The back camera's preview. Each QR code's text goes to [onText] on the main thread. Call only with the permission granted. */
@Composable
internal fun QrCamera(onText: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onText)
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        }
    }
    DisposableEffect(owner) {
        val main = ContextCompat.getMainExecutor(context)
        val worker = Executors.newSingleThreadExecutor()
        controller.setImageAnalysisAnalyzer(worker, QrAnalyzer { text -> main.execute { latest(text) } })
        controller.bindToLifecycle(owner)
        onDispose {
            controller.unbind()
            controller.clearImageAnalysisAnalyzer()
            worker.shutdown()
        }
    }
    AndroidView(
        factory = {
            PreviewView(it).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                // A TextureView, so the rounded clip of the camera box applies to the preview.
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                this.controller = controller
            }
        },
        modifier = modifier,
    )
}

/** Decodes on the analysis thread; reports the same text at most every [REPEAT_MILLIS], so a held code does not flood the main thread. */
private class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }
    private var luminance = ByteArray(0)
    private var lastText: String? = null
    private var lastAt = 0L

    override fun analyze(image: ImageProxy) {
        val text = image.use { decode(it) } ?: return
        val now = System.currentTimeMillis()
        if (text == lastText && now - lastAt < REPEAT_MILLIS) return
        lastText = text
        lastAt = now
        onText(text)
    }

    private fun decode(image: ImageProxy): String? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        // The buffer is reused frame to frame, so it is copied into one reused array.
        if (luminance.size < buffer.remaining()) luminance = ByteArray(buffer.remaining())
        buffer.get(luminance, 0, buffer.remaining())
        val source = PlanarYUVLuminanceSource(luminance, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: NotFoundException) {
            null
        } finally {
            reader.reset()
        }
    }

    private companion object {
        const val REPEAT_MILLIS = 1_500L
    }
}
