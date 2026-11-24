package ru.pokanevich.familybudget.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import ru.pokanevich.familybudget.ui.theme.LocalFamilyPalette
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrScannerDialog(
    onDismiss: () -> Unit,
    onReceiptDetected: (FnsReceiptQrData) -> Unit
) {
    val palette = LocalFamilyPalette.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var manualQrInput by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val isDisposed = remember { AtomicBoolean(false) }
    val handled = remember { AtomicBoolean(false) }
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var imageAnalysisRef by remember { mutableStateOf<ImageAnalysis?>(null) }
    var barcodeScannerRef by remember { mutableStateOf<BarcodeScanner?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            isDisposed.set(true)
            runCatching { imageAnalysisRef?.clearAnalyzer() }
            runCatching { cameraProviderRef?.unbindAll() }
            runCatching { barcodeScannerRef?.close() }
            executor.shutdown()
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = palette.surfaceCard,
            contentColor = palette.textPrimary,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Сканер чека ФНС",
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.textPrimary
                )
                Text(
                    text = "Наведите камеру на QR-код кассового чека — сумма подставится автоматически.",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary
                )

                if (hasCameraPermission) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                            .clip(RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                val previewView = PreviewView(ctx)
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                                cameraProviderFuture.addListener({
                                    if (isDisposed.get()) return@addListener
                                    val cameraProvider = runCatching { cameraProviderFuture.get() }
                                        .onFailure {
                                            errorMsg = "Не удалось инициализировать камеру: ${it.message ?: "ошибка"}"
                                        }
                                        .getOrNull() ?: return@addListener

                                    if (isDisposed.get()) {
                                        runCatching { cameraProvider.unbindAll() }
                                        return@addListener
                                    }

                                    val preview = Preview.Builder().build().also {
                                        it.setSurfaceProvider(previewView.surfaceProvider)
                                    }

                                    val options = BarcodeScannerOptions.Builder()
                                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                        .build()
                                    val scanner = BarcodeScanning.getClient(options)

                                    @Suppress("DEPRECATION")
                                    val analysis = ImageAnalysis.Builder()
                                        .setTargetResolution(Size(1280, 720))
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()

                                    cameraProviderRef = cameraProvider
                                    imageAnalysisRef = analysis
                                    barcodeScannerRef = scanner

                                    analysis.setAnalyzer(executor) { imageProxy ->
                                        @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                                        val mediaImage = imageProxy.image
                                        if (mediaImage != null && !handled.get() && !isDisposed.get()) {
                                            val image = InputImage.fromMediaImage(
                                                mediaImage,
                                                imageProxy.imageInfo.rotationDegrees
                                            )
                                            scanner.process(image)
                                                .addOnSuccessListener { barcodes ->
                                                    for (barcode in barcodes) {
                                                        val raw = barcode.rawValue ?: continue
                                                        val parsed = FnsQrParser.parse(raw)
                                                        if (parsed != null && handled.compareAndSet(false, true)) {
                                                            onReceiptDetected(parsed)
                                                            break
                                                        }
                                                    }
                                                }
                                                .addOnFailureListener {
                                                    // Пропускаем отдельный сбойный кадр, не прерывая поток камеры
                                                }
                                                .addOnCompleteListener {
                                                    imageProxy.close()
                                                }
                                        } else {
                                            imageProxy.close()
                                        }
                                    }

                                    runCatching {
                                        cameraProvider.unbindAll()
                                        cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            analysis
                                        )
                                    }.onFailure {
                                        errorMsg = "Камера недоступна: ${it.message ?: "ошибка привязки"}"
                                    }
                                }, ContextCompat.getMainExecutor(ctx))

                                previewView
                            }
                        )

                        // Corner scanning frame overlay
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val pad = size.minDimension * 0.18f
                            val arm = size.minDimension * 0.14f
                            val stroke = 3.5.dp.toPx()
                            val frameColor = Color(0xFF34D399)

                            val left = (size.width - (size.minDimension - pad * 2)) / 2f
                            val top = pad
                            val right = size.width - left
                            val bottom = size.height - pad

                            // Top-Left
                            drawLine(frameColor, Offset(left, top), Offset(left + arm, top), stroke, StrokeCap.Round)
                            drawLine(frameColor, Offset(left, top), Offset(left, top + arm), stroke, StrokeCap.Round)
                            // Top-Right
                            drawLine(frameColor, Offset(right - arm, top), Offset(right, top), stroke, StrokeCap.Round)
                            drawLine(frameColor, Offset(right, top), Offset(right, top + arm), stroke, StrokeCap.Round)
                            // Bottom-Left
                            drawLine(frameColor, Offset(left, bottom), Offset(left + arm, bottom), stroke, StrokeCap.Round)
                            drawLine(frameColor, Offset(left, bottom - arm), Offset(left, bottom), stroke, StrokeCap.Round)
                            // Bottom-Right
                            drawLine(frameColor, Offset(right - arm, bottom), Offset(right, bottom), stroke, StrokeCap.Round)
                            drawLine(frameColor, Offset(right, bottom - arm), Offset(right, bottom), stroke, StrokeCap.Round)
                        }
                    }
                } else {
                    Text(
                        text = "Нет доступа к камере. Разрешите доступ или вставьте строку QR ниже:",
                        color = palette.accentCoral,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                OutlinedTextField(
                    value = manualQrInput,
                    onValueChange = { manualQrInput = it },
                    label = { Text("Или вставьте текст QR (t=...&s=1450.00)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accentEmerald,
                        unfocusedBorderColor = palette.outlineSubtle
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMsg != null) {
                    Text(
                        text = errorMsg!!,
                        color = palette.accentCoral,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Закрыть", color = palette.textSecondary)
                    }
                    if (manualQrInput.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val parsed = FnsQrParser.parse(manualQrInput.trim())
                                if (parsed != null) {
                                    onReceiptDetected(parsed)
                                } else {
                                    errorMsg = "Не удалось распознать формат ФНС (нужны поля t= и s=)"
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = palette.accentEmerald,
                                contentColor = Color.White
                            )
                        ) {
                            Text("Применить", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
