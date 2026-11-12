package dev.mahali.hatch.feature.terminal

import dev.mahali.hatch.design.Hatch
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.view.WindowManager
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import dev.mahali.hatch.design.Chrome
import dev.mahali.hatch.design.Dimens
import dev.mahali.hatch.design.HeaderButton
import dev.mahali.hatch.design.LocalChrome
import dev.mahali.hatch.design.HatchButton
import dev.mahali.hatch.design.HatchIcons
import dev.mahali.hatch.design.HatchShapes
import dev.mahali.hatch.design.chrome
import dev.mahali.hatch.design.floatingEdge
import dev.mahali.hatch.design.HatchMenuCard
import dev.mahali.hatch.design.HatchMenuItem
import dev.mahali.hatch.design.HatchMenuPopup
import dev.mahali.hatch.design.HatchType
import dev.mahali.hatch.engine.Rex
import dev.mahali.hatch.protocol.RemoteFile
import dev.mahali.hatch.protocol.RexError
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

// Lists the folder on the pane's machine to see if [path] is a regular file; downloads nothing.
internal suspend fun checkFile(rex: Rex, ref: PaneRef, path: String): FileCheck {
    val api = rex.api(ref.host) ?: return FileCheck.Unavailable("Not connected")
    val name = path.substringAfterLast('/')
    if (name.isEmpty()) return FileCheck.Unavailable("It's a folder")
    val listing = runCatching { api.listDir(ref.session, ref.block, path.substringBeforeLast('/').ifEmpty { "/" }, prefix = name, limit = 200) }
        .getOrElse { return FileCheck.Unavailable("Couldn't check the file") }
    val entry = listing.entries.firstOrNull { it.name == name }
    return when {
        listing.status != "ok" || entry == null -> FileCheck.Unavailable("Not found")
        !entry.file -> FileCheck.Unavailable("It's a folder")
        else -> FileCheck.Found
    }
}

private enum class Kind { Video, Image, Text, Pdf, Other }

private val textExtensions = setOf(
    "txt", "md", "markdown", "log", "json", "jsonl", "yaml", "yml", "toml", "ini", "cfg", "conf", "env", "csv", "tsv", "xml",
    "html", "css", "js", "mjs", "ts", "tsx", "jsx", "kt", "kts", "java", "swift", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp",
    "cs", "php", "sh", "bash", "zsh", "fish", "sql", "gradle", "properties", "diff", "patch", "lock",
)

private fun extension(path: String) = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()

private fun kindOf(path: String) = when (val ext = extension(path)) {
    "mp4", "m4v", "mov", "webm", "mkv", "3gp" -> Kind.Video
    "gif", "png", "jpg", "jpeg", "webp", "bmp", "heic", "heif", "avif" -> Kind.Image
    "pdf" -> Kind.Pdf
    in textExtensions -> Kind.Text
    else -> if (path.substringAfterLast('/').let { it == "Makefile" || it == "Dockerfile" || it.startsWith(".") }) Kind.Text else Kind.Other
}

private fun mimeOf(path: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(path))
        ?: if (kindOf(path) == Kind.Text) "text/plain" else "application/octet-stream"

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> megabytes(bytes)
    bytes >= 1_000 -> "%d KB".format(java.util.Locale.ROOT, bytes / 1_000)
    else -> "$bytes bytes"
}

private const val METERED_ASK_BYTES = 20L shl 20

private sealed interface Opened {
    data object Opening : Opened
    class Ready(val file: RemoteFile) : Opened
    class Failed(val message: String) : Opened
}

// Back closes the viewer and stops every transfer. On a wide screen it takes the right half, so the terminal stays in view.
@Composable
internal fun FilePreview(rex: Rex, ref: PaneRef, path: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var opened by remember(rex, ref, path) { mutableStateOf<Opened>(Opened.Opening) }
    LaunchedEffect(rex, ref, path) {
        var owned: RemoteFile? = null
        try {
            withContext(Dispatchers.IO) {
                PreviewCopy.clean(previewDir(context))
                // Assign ownership before the cancellable return to the UI dispatcher.
                owned = rex.openRemoteFile(ref.host, ref.session, path)
            }
            opened = owned?.let { Opened.Ready(it) } ?: Opened.Failed("Not connected")
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RexError) {
            opened = Opened.Failed(e.message ?: "Couldn't open the file")
        } catch (e: Exception) {
            opened = Opened.Failed("Couldn't open the file")
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { owned?.dispose() }
        }
    }

    val wide = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() >= 600.dp }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(wide) {
            if (wide) window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        Row(Modifier.fillMaxSize()) {
            if (wide) Box(Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) { detectTapGestures { onDismiss() } })
            // Dark whatever the app theme, as Photos is.
            CompositionLocalProvider(LocalChrome provides Chrome.NeutralDark) {
                Box(Modifier.weight(1f).fillMaxHeight().background(chrome.background)) {
                    key(rex, ref, path) { Viewer(path, opened, onDismiss) }
                }
            }
        }
    }
}

@Composable
private fun Viewer(path: String, opened: Opened, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(-1f) }
    // The viewer reads only whether a load runs; the bar and card read the fraction, so a chunk redraws only them.
    val loading by remember { derivedStateOf { progress >= 0f } }
    var preparing by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val file = (opened as? Opened.Ready)?.file
    val kind = kindOf(path)
    val name = path.substringAfterLast('/')
    val metered = remember { context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true }
    var allowed by remember(file) { mutableStateOf(file == null || kind == Kind.Video || !metered || file.size <= METERED_ASK_BYTES) }

    fun transfer(label: String, work: suspend ((Float) -> Unit) -> Unit) {
        if (preparing != null) return
        scope.launch {
            preparing = label
            progress = 0f
            try {
                work { progress = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = (e as? NoSpace)?.message ?: "Couldn't copy the file"
            } finally {
                preparing = null
                progress = -1f
            }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeOf(path))) { uri ->
        val f = file
        if (uri != null && f != null) transfer("Saving $name") { report ->
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { copy(f, it, report) } ?: throw IOException() }
        }
    }
    fun export(share: Boolean) {
        val f = file ?: return
        transfer(if (share) "Preparing to share" else "Preparing to open") { handOver(context, f, name, share, it) }
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(end = Hatch.space.xs), verticalAlignment = Alignment.CenterVertically) {
                HeaderButton(HatchIcons.ChevronLeft, "Close", onDismiss)
                Column(Modifier.weight(1f)) {
                    Text(name, style = HatchType.Body.copy(fontWeight = FontWeight.SemiBold), color = chrome.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        file?.let { "${formatSize(it.size)} · ${path.substringBeforeLast('/')}" } ?: "Opening…",
                        style = Hatch.type.mono, color = chrome.secondary,
                        maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                    )
                }
                if (file != null) Box {
                    HeaderButton(HatchIcons.MoreHorizontal, "More", { if (preparing == null) menu = true })
                    if (menu) HatchMenuPopup({ menu = false }) {
                        HatchMenuCard {
                            HatchMenuItem("Open with…", { menu = false; export(share = false) }, HatchIcons.OpenExternal)
                            HatchMenuItem("Share", { menu = false; export(share = true) }, HatchIcons.Move)
                            HatchMenuItem("Save to device", { menu = false; save.launch(name) }, HatchIcons.Picture)
                            HatchMenuItem("Copy path", { menu = false; context.copyText(path) }, HatchIcons.Copy)
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (opened is Opened.Opening) LinearProgressIndicator(Modifier.fillMaxSize(), color = chrome.accent, trackColor = chrome.stroke)
                else if (loading && preparing == null) LinearProgressIndicator({ progress }, Modifier.fillMaxSize(), color = chrome.accent, trackColor = chrome.stroke, drawStopIndicator = {})
            }
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                val failed = (opened as? Opened.Failed)?.message ?: error
                when {
                    failed != null -> FileCard(name, null, failed, file?.let { { export(share = false) } })
                    file == null -> {}
                    !allowed -> FileCard(name, file.size, "This is ${formatSize(file.size)} over mobile data", null) { allowed = true }
                    else -> when (kind) {
                        Kind.Video -> VideoView(file) { error = it }
                        Kind.Image -> ImageView(file, { progress = it }) { error = it }
                        Kind.Text -> TextView(file, { progress = it }) { error = it }
                        Kind.Pdf -> PdfView(file, { progress = it }) { error = it }
                        Kind.Other -> FileCard(name, file.size, null, { export(share = false) })
                    }
                }
            }
        }
        val label = preparing
        if (label != null && file != null) {
            TransferCard(label, { progress }, file.size, Modifier.align(Alignment.BottomCenter).padding(bottom = Hatch.space.xxl))
        }
    }
}

@Composable
private fun TransferCard(label: String, progress: () -> Float, size: Long, modifier: Modifier) =
    UploadProgress(UploadPill.Sending(label, (progress().coerceAtLeast(0f) * size).toLong(), size), modifier)

@Composable
private fun FileCard(name: String, size: Long?, message: String?, onOpenWith: (() -> Unit)?, onContinue: (() -> Unit)? = null) {
    Column(
        Modifier.padding(Dimens.ScreenInset).width(260.dp).clip(HatchShapes.Card).background(chrome.card).floatingEdge(HatchShapes.Card).padding(Hatch.space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Hatch.space.xs),
    ) {
        Box(Modifier.size(52.dp, 64.dp).clip(HatchShapes.Small).background(chrome.stroke), contentAlignment = Alignment.BottomCenter) {
            Text(extension(name).uppercase().take(4), Modifier.padding(bottom = Hatch.space.s), style = Hatch.type.mono, color = chrome.secondary)
        }
        Spacer(Modifier.height(Hatch.space.s))
        Text(name, style = HatchType.Body.copy(fontWeight = FontWeight.SemiBold), color = chrome.text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (size != null) Text(formatSize(size), style = Hatch.type.mono, color = chrome.secondary)
        if (message != null) Text(message, style = HatchType.Footnote, color = chrome.secondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Hatch.space.s))
        if (onContinue != null) HatchButton("Preview anyway", onContinue, Modifier.fillMaxWidth())
        if (onOpenWith != null) {
            val primary = onContinue == null
            HatchButton(
                "Open with…", onOpenWith, Modifier.fillMaxWidth(),
                container = if (primary) chrome.accent else chrome.control, content = if (primary) chrome.onAccent else chrome.text,
            )
        }
    }
}

// Video: streamed through the helper, nothing written to disk

@OptIn(UnstableApi::class)
@Composable
private fun VideoView(file: RemoteFile, onError: (String) -> Unit) {
    val context = LocalContext.current
    val player = remember(file) {
        val limits = previewLimits
        // Media3's default buffer can reach 100+ MB of RAM; cap it by bytes as well as by time.
        val load = DefaultLoadControl.Builder().setTargetBufferBytes(limits.videoBufferBytes).setPrioritizeTimeOverSizeThresholds(false).build()
        ExoPlayer.Builder(context).setLoadControl(load).build().apply {
            setMediaSource(ProgressiveMediaSource.Factory { RemoteFileDataSource(file, limits.chunkBytes) }.createMediaSource(MediaItem.fromUri("hatch-preview://file")))
            addListener(object : Player.Listener {
                // Often a codec this phone can't decode (4K, HEVC on low-end hardware); Open with may still play it.
                override fun onPlayerError(error: PlaybackException) = onError("This phone can't play this video")
            })
            prepare()
            playWhenReady = true
        }
    }
    // Decoders are few and shared with the system: give it back at once, and pause in the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.release() }
    }
    AndroidView({ PlayerView(it).apply { this.player = player } }, Modifier.fillMaxSize())
}

// Media3 reads through the helper: each open is a seek, each chunk one range request.
@UnstableApi
private class RemoteFileDataSource(private val file: RemoteFile, private val chunkBytes: Int) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var opened = false
    private var position = 0L
    private var remaining = 0L
    private var chunk = ByteArray(0)
    private var used = 0

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        position = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) file.size - position else dataSpec.length
        chunk = ByteArray(0); used = 0
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        if (used == chunk.size) {
            // Media3 calls this on its own loading thread, never the main one.
            chunk = try {
                runBlocking { file.read(position, minOf(chunkBytes.toLong(), remaining).toInt()) }
            } catch (e: Exception) {
                throw IOException(e)
            }
            used = 0
            if (chunk.isEmpty()) return C.RESULT_END_OF_INPUT
        }
        val n = minOf(length.toLong(), (chunk.size - used).toLong(), remaining).toInt()
        chunk.copyInto(buffer, offset, used, used + n)
        used += n; position += n; remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        chunk = ByteArray(0)
        if (opened) { opened = false; transferEnded() }
        uri = null
    }
}

// Images and GIFs: read once into one array, decoded no larger than the screen.
// Refused before anything is allocated for it.

private const val MAX_PIXELS = 50_000_000L

@Composable
private fun ImageView(file: RemoteFile, onProgress: (Float) -> Unit, onError: (String) -> Unit) {
    val resources = LocalResources.current
    val window = LocalWindowInfo.current.containerSize
    val screen = maxOf(window.width, window.height).coerceAtLeast(1)
    val drawable by produceState<Drawable?>(null, file, screen) {
        if (file.size > previewLimits.imageBytes) { onError("Too large to preview on this phone"); return@produceState }
        value = try {
            withContext(Dispatchers.IO) {
                val bytes = readAll(file, onProgress)
                withContext(Dispatchers.Default) { decode(bytes, screen, previewLimits.lowRam, resources) }
            }
        } catch (e: TooLarge) {
            onError("Too large to preview"); null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError("Not an image this phone can show"); null
        } finally {
            onProgress(-1f)
        }
    }
    val d = drawable ?: return
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoom = rememberTransformableState { change, pan, _ ->
        scale = (scale * change).coerceIn(1f, 8f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    DisposableEffect(d) {
        (d as? Animatable)?.start()
        onDispose { (d as? Animatable)?.stop() }
    }
    AndroidView(
        { android.widget.ImageView(it).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER } },
        Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero }) }
            .transformable(zoom)
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
        update = { it.setImageDrawable(d) },
    )
}

private class TooLarge : Exception()

// Reads into one array of exactly the file's size: one copy, never a growing buffer.
private suspend fun readAll(file: RemoteFile, onProgress: (Float) -> Unit): ByteArray {
    val bytes = ByteArray(file.size.toInt())
    var at = 0
    while (at < bytes.size) {
        val part = file.read(at.toLong(), minOf(previewLimits.chunkBytes, bytes.size - at))
        if (part.isEmpty()) throw IOException("The file got shorter")
        part.copyInto(bytes, at)
        at += part.size
        onProgress(at.toFloat() / bytes.size)
    }
    return bytes
}

private fun decode(bytes: ByteArray, screen: Int, lowRam: Boolean, resources: Resources): Drawable =
    if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val w = info.size.width.toLong()
            val h = info.size.height.toLong()
            if (w * h > MAX_PIXELS) throw TooLarge()
            val longest = maxOf(w, h)
            if (longest > screen) decoder.setTargetSize((w * screen / longest).toInt().coerceAtLeast(1), (h * screen / longest).toInt().coerceAtLeast(1))
            if (lowRam) decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
        }
    } else {
        // Before Android 9 there is no ImageDecoder: a GIF shows its first frame, and Open with plays it.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) throw TooLarge()
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= screen) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            if (lowRam) inPreferredConfig = Bitmap.Config.RGB_565
        }
        (BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: throw IOException("Not an image")).toDrawable(resources)
    }

@Composable
private fun TextView(file: RemoteFile, onProgress: (Float) -> Unit, onError: (String) -> Unit) {
    val limit = previewLimits.textBytes
    val lines by produceState<List<String>?>(null, file) {
        value = try {
            withContext(Dispatchers.IO) {
                val n = minOf(file.size, limit.toLong()).toInt()
                val bytes = ByteArray(n)
                var at = 0
                while (at < n) {
                    val part = file.read(at.toLong(), minOf(previewLimits.chunkBytes, n - at))
                    if (part.isEmpty()) break
                    part.copyInto(bytes, at); at += part.size
                    onProgress(at.toFloat() / n)
                }
                if (bytes.take(8192).any { it == 0.toByte() }) { onError("A binary file, not text"); return@withContext null }
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(bytes, 0, at)).toString()
                text.split('\n').flatMap(::displayLines)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError("Couldn't read the file"); null
        } finally {
            onProgress(-1f)
        }
    }
    val shown = lines ?: return
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = Hatch.space.m)) {
        itemsIndexed(shown) { _, line ->
            Text(line, style = Hatch.type.mono, color = chrome.text)
        }
        if (file.size > limit) item {
            Text("Showing the first ${formatSize(limit.toLong())} of ${formatSize(file.size)}", Modifier.padding(vertical = Hatch.space.l), style = HatchType.Footnote, color = chrome.secondary)
        }
    }
}

// Splits at most [max] chars apart, never mid surrogate pair, so a huge line (minified JSON) doesn't stall scrolling.
internal fun displayLines(line: String, max: Int = MAX_DISPLAY_LINE): List<String> {
    if (line.length <= max) return listOf(line)
    val pieces = ArrayList<String>(line.length / max + 1)
    var at = 0
    while (at < line.length) {
        var end = minOf(at + max, line.length)
        if (end < line.length && end - 1 > at && line[end - 1].isHighSurrogate()) end--
        pieces += line.substring(at, end)
        at = end
    }
    return pieces
}

private const val MAX_DISPLAY_LINE = 2_000

// PdfRenderer needs a seekable file, so the preview is copied to a temporary file first.

@Composable
private fun PdfView(file: RemoteFile, onProgress: (Float) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val document by produceState<PdfDocument?>(null, file) {
        var owned: PdfDocument? = null
        try {
            withContext(Dispatchers.IO) {
                val local = temporaryCopy(context, file, "preview.pdf", onProgress)
                try {
                    val descriptor = ParcelFileDescriptor.open(local.file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = try { PdfRenderer(descriptor) } catch (e: Throwable) { descriptor.close(); throw e }
                    // Retain ownership even if cancellation wins the return to the UI thread.
                    owned = try { PdfDocument(local, renderer) } catch (e: Throwable) { renderer.close(); throw e }
                } catch (e: Throwable) {
                    local.close()
                    throw e
                }
            }
            value = owned
            onProgress(-1f)
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoSpace) {
            onError(e.message!!)
        } catch (e: Exception) {
            onError("Couldn't open this PDF")
        } finally {
            withContext(NonCancellable) { owned?.close() }
            onProgress(-1f)
        }
    }
    val doc = document ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = with(LocalDensity.current) { maxWidth.roundToPx() }.let { if (previewLimits.lowRam) minOf(it, 1080) else it }
        // Only the pages on screen hold a bitmap: LazyColumn drops the rest, and each frees its own.
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Hatch.space.s)) {
            items(doc.pages) { index ->
                key(doc, index, width) { PdfPage(doc, index, width.coerceAtLeast(1)) }
            }
        }
    }
}

private sealed interface PageImage {
    data object Loading : PageImage
    class Ready(val bitmap: Bitmap) : PageImage
    data object Failed : PageImage
}

@Composable
private fun PdfPage(doc: PdfDocument, index: Int, width: Int) {
    val page by produceState<PageImage>(PageImage.Loading, doc, index, width) {
        var owned: Bitmap? = null
        try {
            // Ownership is assigned on the rendering thread, before its cancellable return.
            doc.render(index, width) { owned = it }
            value = owned?.let { PageImage.Ready(it) } ?: PageImage.Failed
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            value = PageImage.Failed
        } finally {
            owned?.recycle()
        }
    }
    when (val image = page) {
        PageImage.Loading -> Box(Modifier.fillMaxWidth().aspectRatio(doc.aspect(index)).background(chrome.card))
        PageImage.Failed -> Text("Couldn't render page ${index + 1}", Modifier.padding(Dimens.ScreenInset), style = HatchType.Footnote, color = chrome.text)
        is PageImage.Ready -> Image(image.bitmap.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxWidth().background(Color.White), contentScale = ContentScale.FillWidth)
    }
}

private class NoSpace(message: String) : IOException(message)

private fun previewDir(context: Context) = File(context.cacheDir, "previews").apply { mkdirs() }

// Reserves the size first: Android frees other apps' caches to make room, and nothing downloads if it can't.
private suspend fun temporaryCopy(context: Context, file: RemoteFile, name: String, onProgress: (Float) -> Unit): PreviewCopy {
    val local = PreviewCopy.create(previewDir(context), name)
    try {
        withContext(Dispatchers.IO) {
            FileOutputStream(local.file).use { out ->
                try {
                    context.getSystemService(StorageManager::class.java).allocateBytes(out.fd, file.size)
                } catch (e: IOException) {
                    throw NoSpace("Not enough space on this phone for ${formatSize(file.size)}")
                }
                copy(file, out, onProgress)
            }
        }
        return local
    } catch (e: Throwable) {
        local.close()
        throw e
    }
}

/** Streams [file] into [out] range by range; only one range is ever in memory. */
private suspend fun copy(file: RemoteFile, out: OutputStream, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
    var at = 0L
    while (at < file.size) {
        val part = file.read(at, minOf(previewLimits.chunkBytes.toLong(), file.size - at).toInt())
        if (part.isEmpty()) throw IOException("The file got shorter")
        out.write(part)
        at += part.size
        onProgress(at.toFloat() / file.size)
    }
}

// Open with or Share, through a content:// URI with a one-off read grant.
private suspend fun handOver(context: Context, file: RemoteFile, name: String, share: Boolean, onProgress: (Float) -> Unit) {
    val local = temporaryCopy(context, file, name, onProgress)
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.previews", local.file)
        val mime = mimeOf(name)
        val intent = if (share) {
            Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).apply { clipData = ClipData.newRawUri(name, uri) }
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        withContext(Dispatchers.Main) {
            context.startActivity(Intent.createChooser(intent, if (share) "Share $name" else "Open $name with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // Retain before this dispatch can return with cancellation: the recipient now owns a read grant.
            local.retain()
        }
    } finally {
        local.close()
    }
}
