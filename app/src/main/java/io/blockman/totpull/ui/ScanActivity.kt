package io.blockman.totpull.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import io.blockman.totpull.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text

/**
 * In-app QR scanner. The camera feed and decoding still come from the
 * zxing-android-embedded library (BarcodeView — camera session + decode loop),
 * but the shell UI is our own Compose screen instead of the library's
 * default CaptureActivity, so the look matches the rest of the app.
 */
class ScanActivity : AppCompatActivity() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(io.blockman.totpull.LocaleHelper.wrap(base))
    }

    companion object {
        const val EXTRA_TEXT = "extra_text"
    }

    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            QrScanScreen(
                onResult = { text ->
                    if (!delivered) {
                        delivered = true
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
                        finish()
                    }
                },
                onClose = { finish() }
            )
        }
    }
}

/**
 * Decodes a QR code from a picked gallery image. Huge photos are downsampled before
 * decoding; if the straight binarization finds nothing the image is retried inverted,
 * which covers dark-mode screenshots with light codes.
 */
private fun decodeQrFromUri(context: Context, uri: Uri): String? {
    return try {
        val resolver = context.contentResolver

        // First pass: measure without allocating pixels. decodeStream returns null in this
        // mode on purpose, so the stream must not be treated as failed when the result is
        // null - only bounds validity says whether the image was understood.
        val boundsProbe = resolver.openInputStream(uri) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        boundsProbe.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0) return null

        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxSide / (sample * 2) >= 1600) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decodeStream = resolver.openInputStream(uri) ?: return null
        val bmp = decodeStream.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        bmp.recycle()
        val source = RGBLuminanceSource(bmp.width, bmp.height, pixels)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)

        try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (first: Exception) {
            try {
                // Inverted luminance: retry without recycling the same reader state.
                QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text
            } catch (second: Exception) {
                null
            }
        }
    } catch (t: Throwable) {
        null
    }
}

private fun viewfinderRect(size: Size, densityPx: Float): Rect {
    val side = minOf(size.width, size.height) * 0.72f
    val cx = size.width / 2f
    val cy = size.height * 0.42f
    return Rect(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
}

/**
 * Aggressive one-shot decode for the freeze-frame button: tries both binarizers, both
 * polarities and quarter rotations. The continuous loop runs the cheap path only, so this
 * catches frames it missed (low-contrast screen codes, slightly rotated captures).
 */
private fun deepDecode(bmp: android.graphics.Bitmap): String? {
    return try {
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        var source: com.google.zxing.LuminanceSource =
                RGBLuminanceSource(bmp.width, bmp.height, pixels)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)

        repeat(4) {
            // invert() and rotation live on the luminance source; binarizers wrap it.
            for (base in listOf(source, source.invert())) {
                for (binarizer in listOf(
                        HybridBinarizer(base),
                        com.google.zxing.common.GlobalHistogramBinarizer(base))) {
                    try {
                        QRCodeReader().decode(BinaryBitmap(binarizer), hints)
                                .text?.let { return it }
                    } catch (e: Exception) {
                    }
                }
            }
            source = source.rotateCounterClockwise()
        }
        null
    } catch (t: Throwable) {
        null
    }
}

@Composable
private fun QrScanScreen(onResult: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> granted = ok }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            ScannerContent(onResult = onResult, onClose = onClose)
        } else {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                Text(
                    stringResource(R.string.scan_camera_permission),
                    color = Color.White,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = { launcher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.padding(top = 20.dp)
                ) {
                    Text(stringResource(R.string.scan_grant_camera))
                }
            }
        }
    }
}

@Composable
private fun ScannerContent(onResult: (String) -> Unit, onClose: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val prompt = stringResource(R.string.scan_prompt)
    val scanner = remember { mutableStateOf<BarcodeView?>(null) }
    // Pinch-to-zoom state: a normalized 0..1 fraction of the camera's zoom range, applied
    // through changeCameraParameters because zxing-embedded 4.3.0 has no zoom API of its own.
    var zoomFraction by remember { mutableStateOf(0f) }
    val maxRatio = 6f

    fun applyZoom(fraction: Float) {
        zoomFraction = fraction.coerceIn(0f, 1f)
        scanner.value?.changeCameraParameters { params ->
            if (params.isZoomSupported) {
                params.setZoom((params.maxZoom * zoomFraction).toInt())
            }
            params
        }
    }

    // Multi-page migration buffer: pages collected in this session, keyed by the payload's
    // zero-based batch_index. Collecting every page (or tapping 直接导入) hands the joined
    // URIs through the normal import pipeline instead of jumping into the manager early.
    var bufferBatchId by remember { mutableStateOf<Long?>(null) }
    var bufferSize by remember { mutableStateOf(0) }
    var bufferedPages by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var bufferedCounts by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }

    fun handleScanText(text: String) {
        if (text.contains("otpauth-migration://")) {
            val batch = io.blockman.totpull.format.MigrationImporter.parseBatch(text)
            if (batch.size > 1 && batch.entries.isNotEmpty()) {
                if (bufferBatchId != null && bufferBatchId != batch.id) {
                    // A different export round: start a fresh buffer.
                    bufferedPages = emptyMap()
                    bufferedCounts = emptyMap()
                }
                val isNew = !bufferedPages.containsKey(batch.index)
                bufferBatchId = batch.id
                bufferSize = batch.size
                bufferedPages = bufferedPages + (batch.index to text)
                bufferedCounts = bufferedCounts + (batch.index to batch.entries.size)
                if (isNew) {
                    Toast.makeText(context,
                            context.getString(R.string.scan_collected_page,
                            batch.index + 1, batch.size, batch.entries.size),
                            Toast.LENGTH_SHORT).show()
                }
                if (bufferedPages.size >= batch.size) {
                    onResult(bufferedPages.toSortedMap().values.joinToString("\n"))
                }
                return
            }
        }
        onResult(text)
    }

    // Gallery import: same exit path as a camera hit, so the picked image flows through
    // the identical import pipeline afterwards.
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
        val text = decodeQrFromUri(context, uri)
        if (text != null) {
            handleScanText(text)
        } else {
            Toast.makeText(context, context.getString(R.string.scan_gallery_no_qr),
                    Toast.LENGTH_SHORT).show()
        }
    }

    // System camera capture: hand off to the full-featured camera app, then decode the
    // saved JPEG through the same pipeline. Files go into our own FileProvider scope.
    var captureFile by remember { mutableStateOf<java.io.File?>(null) }
    val systemCapture = rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture()) { ok ->
        val file = captureFile
        if (ok && file != null) {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, context.packageName + ".fileprovider", file)
            val text = decodeQrFromUri(context, uri)
            if (text != null) {
                handleScanText(text)
            } else {
                Toast.makeText(context, context.getString(R.string.scan_gallery_no_qr),
                        Toast.LENGTH_SHORT).show()
            }
        }
    }
    fun launchSystemCapture() {
        try {
            val dir = java.io.File(context.cacheDir, "captured").apply { mkdirs() }
            val file = java.io.File(dir, "capture_" + System.currentTimeMillis() + ".jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, context.packageName + ".fileprovider", file)
            captureFile = file
            systemCapture.launch(uri)
        } catch (t: Throwable) {
            Toast.makeText(context, context.getString(R.string.scan_capture_failed),
                    Toast.LENGTH_SHORT).show()
        }
    }

    // Freeze frame: grab the current preview frame through the camera instance and give it
    // the deep decode (every binarizer and rotation) that the continuous loop skips.
    fun freezeAndDecode() {
        val instance = scanner.value?.cameraInstance ?: return
        instance.requestPreview(object :
                com.journeyapps.barcodescanner.camera.PreviewCallback {
            override fun onPreview(data: com.journeyapps.barcodescanner.SourceData) {
                val text = try {
                    deepDecode(data.getBitmap())
                } catch (t: Throwable) {
                    null
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (text != null) {
                        handleScanText(text)
                    } else {
                        Toast.makeText(context,
                                context.getString(R.string.scan_gallery_no_qr),
                                Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onPreviewError(e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, context.getString(R.string.scan_capture_failed),
                            Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> scanner.value?.resume()
                Lifecycle.Event.ON_PAUSE -> scanner.value?.pause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize()) {
    AndroidView(
        factory = { ctx ->
            BarcodeView(ctx).apply {
                // TRY_HARDER: migration export QRs pack a whole protobuf payload and get
                // large (100+ modules a side); without it zxing often gives up at the
                // camera preview's resolution. There is no entry filtering here on purpose -
                // otpauth / otpauth-migration / any text lands in the import pipeline.
                val hints = mapOf(com.google.zxing.DecodeHintType.TRY_HARDER to true)
                setDecoderFactory(DefaultDecoderFactory(
                        listOf(BarcodeFormat.QR_CODE), hints, null, 0))
                decodeContinuous(object : BarcodeCallback {
                    override fun barcodeResult(result: BarcodeResult) {
                        val text = result.text?.takeIf { it.isNotEmpty() } ?: return
                        // The callback runs on the decode thread; state mutations and
                        // toasts must land on the main thread.
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            handleScanText(text)
                        }
                    }

                    override fun possibleResultPoints(resultPoints: List<com.google.zxing.ResultPoint>) {}
                })
                resume()
                scanner.value = this
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1f) {
                        val ratio = 1f + zoomFraction * (maxRatio - 1f)
                        applyZoom(((ratio * zoom) - 1f) / (maxRatio - 1f))
                    }
                }
            }
    )

    // Dimmed scrim with a transparent square cut-out for the viewfinder.
    Canvas(Modifier.fillMaxSize()) {
        val box = viewfinderRect(size, 1f)
        val scrim = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRect(box)
        }
        drawPath(scrim, Color.Black.copy(alpha = 0.55f))

        val lineWidth = 5.dp.toPx()
        val corner = box.width * 0.12f
        // Four corner brackets in the app accent tone.
        val accent = Color(0xFF3C7DFF)
        drawLine(accent, Offset(box.left, box.top + corner), Offset(box.left, box.top), lineWidth, StrokeCap.Round)
        drawLine(accent, Offset(box.left, box.top), Offset(box.left + corner, box.top), lineWidth, StrokeCap.Round)
        drawLine(accent, Offset(box.right - corner, box.top), Offset(box.right, box.top), lineWidth, StrokeCap.Round)
        drawLine(accent, Offset(box.right, box.top), Offset(box.right, box.top + corner), lineWidth, StrokeCap.Round)
        drawLine(accent, Offset(box.left, box.bottom - corner), Offset(box.left, box.bottom), lineWidth, StrokeCap.Round)
        drawLine(accent, Offset(box.left, box.bottom), Offset(box.left + corner, box.bottom), lineWidth, StrokeCap.Round)
        drawLine(accent, Offset(box.right - corner, box.bottom), Offset(box.right, box.bottom), lineWidth, StrokeCap.Round)
        drawLine(accent, Offset(box.right, box.bottom), Offset(box.right, box.bottom - corner), lineWidth, StrokeCap.Round)
    }

    // Scan line sweeping inside the viewfinder.
    val transition = rememberInfiniteTransition(label = "scan")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sweep"
    )
    Canvas(Modifier.fillMaxSize()) {
        val box = viewfinderRect(size, 1f)
        val y = box.top + (box.bottom - box.top) * sweep
        drawLine(
            Color.White.copy(alpha = 0.85f),
            Offset(box.left + box.width * 0.06f, y),
            Offset(box.right - box.width * 0.06f, y),
            strokeWidth = 2.5.dp.toPx()
        )
    }

    // Top bar: close button + gallery picker.
    Text(
        "✕",
        color = Color.White,
        fontSize = 26.sp,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(start = 12.dp, top = 44.dp)
            .size(44.dp)
            .clickable { onClose() }
            .padding(top = 2.dp)
    )
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(end = 12.dp, top = 44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.scan_capture),
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .size(52.dp)
                .clickable { launchSystemCapture() }
                .padding(top = 10.dp)
        )
        Text(
            stringResource(R.string.scan_gallery),
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .size(44.dp)
                .clickable { pickImage.launch("image/*") }
                .padding(top = 10.dp)
        )
    }

    // Zoom ratio indicator, visible only after the user has actually pinched.
    if (zoomFraction > 0f) {
        Text(
            "×%.1f".format(1f + zoomFraction * (maxRatio - 1f)),
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 52.dp)
        )
    }

    // Multi-page collection progress, bottom-left; jump out early via 直接导入 bottom-right.
    if (bufferSize > 0 && bufferedPages.isNotEmpty()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 150.dp)
        ) {
            for (page in 1..bufferSize) {
                val got = bufferedPages.containsKey(page - 1)
                val count = bufferedCounts[page - 1]
                Text(
                    if (got) stringResource(R.string.scan_page_done, page, count ?: 0)
                    else stringResource(R.string.scan_page_pending, page),
                    color = if (got) Color(0xFF66D17A)
                            else Color.White.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
            Text(
                stringResource(R.string.scan_batch_status,
                        bufferedCounts.values.sum(), bufferedPages.size, bufferSize),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Text(
            stringResource(R.string.scan_import_direct),
            color = Color(0xFF8FB6FF),
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 150.dp)
                .background(Color.Black.copy(alpha = 0.4f),
                        androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .clickable {
                    if (bufferedPages.isNotEmpty()) {
                        onResult(bufferedPages.toSortedMap().values.joinToString("\n"))
                    }
                }
                .padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }

    // Freeze-frame shutter: a circular button over a larger translucent ring. Pressing
    // shrinks the white dot only (the ring stays put); a clean release over-swings past
    // 1.0 before settling (same rebound language as the card/FAB press feedback) and
    // fires the deep decode.
    val shutterScale = remember { androidx.compose.animation.core.Animatable(1f) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 84.dp)
            .size(76.dp)
    ) {
        Box(
            Modifier
                .size(76.dp)
                .background(Color.Gray.copy(alpha = 0.4f),
                        androidx.compose.foundation.shape.CircleShape)
        )
        Box(
            Modifier
                .size(64.dp)
                .graphicsLayer {
                    scaleX = shutterScale.value
                    scaleY = shutterScale.value
                }
                .background(Color.White,
                        androidx.compose.foundation.shape.CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(
                            onPress = {
                                shutterScale.animateTo(0.9f,
                                        androidx.compose.animation.core.tween(100))
                                val released = try {
                                    awaitRelease()
                                    true
                                } catch (e: Exception) {
                                    false
                                }
                                if (released) {
                                    shutterScale.animateTo(1.06f,
                                            androidx.compose.animation.core.tween(130))
                                    shutterScale.animateTo(1f,
                                            androidx.compose.animation.core.spring(
                                                    Spring.DampingRatioMediumBouncy,
                                                    Spring.StiffnessMediumLow))
                                    freezeAndDecode()
                                } else {
                                    shutterScale.animateTo(1f,
                                            androidx.compose.animation.core.spring(
                                                    Spring.DampingRatioNoBouncy,
                                                    Spring.StiffnessMedium))
                                }
                            })
                }
        )
    }

    // Prompt near the bottom.
    Text(
        prompt,
        color = Color.White.copy(alpha = 0.9f),
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(bottom = 56.dp)
    )
    }
}
