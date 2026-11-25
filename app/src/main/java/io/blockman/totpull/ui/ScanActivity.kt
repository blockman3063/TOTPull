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
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0) return null

        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxSide / (sample * 2) >= 1600) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = resolver.openInputStream(uri)?.use {
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
                    "需要相机权限才能扫码",
                    color = Color.White,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = { launcher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.padding(top = 20.dp)
                ) {
                    Text("授权相机")
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

    // Gallery import: same exit path as a camera hit, so the picked image flows through
    // the identical import pipeline afterwards.
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
        val text = decodeQrFromUri(context, uri)
        if (text != null) {
            onResult(text)
        } else {
            Toast.makeText(context, context.getString(R.string.scan_gallery_no_qr),
                    Toast.LENGTH_SHORT).show()
        }
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
                        result.text?.takeIf { it.isNotEmpty() }?.let(onResult)
                    }

                    override fun possibleResultPoints(resultPoints: List<com.google.zxing.ResultPoint>) {}
                })
                resume()
                scanner.value = this
            }
        },
        modifier = Modifier.fillMaxSize()
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
    Text(
        "相册",
        color = Color.White.copy(alpha = 0.9f),
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(end = 12.dp, top = 44.dp)
            .size(44.dp)
            .clickable { pickImage.launch("image/*") }
            .padding(top = 10.dp)
    )

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
