package com.feederp.ui.common

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * বারকোড/QR স্ক্যান (CameraX + ML Kit, ফোনেই অফলাইনে চলে)। প্রথম পাওয়া মান [onResult]-এ দিয়ে বন্ধ হয়।
 * ক্যামেরার অনুমতি না দিলে বার্তা দেখায়। আইটেমের কোড ([com.feederp.data.ItemEntity.code]) বারকোড হিসেবে মেলানো হয়।
 * কম্পাইল ও ডিভাইসে পরীক্ষা হয়নি।
 */
@Composable
fun BarcodeScannerDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = androidx.compose.material3.MaterialTheme.shapes.medium) {
            Column(Modifier.padding(12.dp)) {
                Text("বারকোড/QR ক্যামেরার সামনে ধরুন")
                if (granted) CameraPreview(onResult) else Text("ক্যামেরার অনুমতি দেওয়া হয়নি; ফোনের সেটিংসে অ্যাপকে ক্যামেরা অনুমতি দিন।", Modifier.padding(vertical = 12.dp))
                TextButton(onClick = onDismiss) { Text("বন্ধ") }
            }
        }
    }
}

@Composable
private fun CameraPreview(onResult: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }
    val done = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) { onDispose { executor.shutdown(); scanner.close() } }

    AndroidView(
        modifier = Modifier.fillMaxWidth().height(320.dp),
        factory = { c ->
            val view = PreviewView(c)
            val future = ProcessCameraProvider.getInstance(c)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null || done.get()) { proxy.close(); return@setAnalyzer }
                    scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            val v = codes.firstNotNullOfOrNull { it.rawValue?.takeIf { s -> s.isNotBlank() } }
                            if (v != null && done.compareAndSet(false, true)) onResult(v)
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (_: Exception) { /* ক্যামেরা পাওয়া যায়নি */ }
            }, ContextCompat.getMainExecutor(c))
            view
        },
    )
}
