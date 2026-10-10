package com.scantype.documentscanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File

enum class CamMode(val label: String) { DOCS("Docs"), ID("ID Card"), TEXT("To Text"), HAND("Handwriting") }

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(if (selected) Cyan else Color(0x33FFFFFF))
        .clickable { onClick() }.padding(horizontal = 16.dp, vertical = 7.dp)) {
        Text(text, color = if (selected) Navy else Color.White, fontSize = 14.sp)
    }
}

/** ScanType's own camera. Asks for the CAMERA permission itself and explains what to do if it is denied. */
@Composable
fun CameraScreen(mode: CamMode, onMode: (CamMode) -> Unit, batch: Boolean, onBatch: (Boolean) -> Unit, aiReady: Boolean,
                 onDone: (List<Uri>, CamMode) -> Unit, onClose: () -> Unit, onSmart: () -> Unit) {
    val ctx = LocalContext.current
    val owner = ctx as ComponentActivity
    fun has() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(has()) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; if (!ok) denied = true }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { us ->
        if (us.isNotEmpty()) onDone(us, mode)
    }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = has() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    if (granted) CameraContent(mode, onMode, batch, onBatch, aiReady, onDone, onClose, onSmart)
    else Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text(if (denied) "Camera permission was denied. Allow it to scan with the camera, or choose images from your gallery."
        else "ScanType needs the camera only to photograph your documents. Photos stay on your device.")
        Spacer(Modifier.height(16.dp))
        Button({ ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
        OutlinedButton({
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
        }) { Text("Open app settings") }
        OutlinedButton({ pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Choose from gallery") }
        TextButton(onSmart) { Text("Use the Google scanner instead") }
        TextButton(onClose) { Text("Close") }
    }
}

@Composable
private fun CameraContent(mode: CamMode, onMode: (CamMode) -> Unit, batch: Boolean, onBatch: (Boolean) -> Unit, aiReady: Boolean,
                          onDone: (List<Uri>, CamMode) -> Unit, onClose: () -> Unit, onSmart: () -> Unit) {
    val ctx = LocalContext.current
    val owner = ctx as ComponentActivity
    val shots = remember { mutableStateListOf<Uri>() }
    var flash by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    LaunchedEffect(flash) { capture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF }
    LaunchedEffect(mode) { shots.clear() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { us ->
        if (us.isNotEmpty()) onDone(us, mode)
    }

    fun shoot() {
        if (busy) return
        busy = true
        val f = File(ctx.cacheDir, "cap_${System.currentTimeMillis()}.jpg")
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    busy = false
                    val u = Uri.fromFile(f)
                    if (mode == CamMode.ID) { shots.add(u); if (shots.size >= 2) onDone(shots.toList(), mode) }
                    else if (batch) shots.add(u) else onDone(listOf(u), mode)
                }
                override fun onError(e: ImageCaptureException) {
                    busy = false
                    Toast.makeText(ctx, "The photo could not be taken. Please try again.", Toast.LENGTH_SHORT).show()
                }
            })
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
            PreviewView(c).apply {
                scaleType = PreviewView.ScaleType.FIT_CENTER
                val fut = ProcessCameraProvider.getInstance(c)
                fut.addListener({
                    runCatching {
                        val provider = fut.get()
                        val pv = Preview.Builder().build()
                        pv.setSurfaceProvider(surfaceProvider)
                        provider.unbindAll()
                        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, pv, capture)
                    }
                }, ContextCompat.getMainExecutor(c))
            }
        })
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
            TextButton(onSmart) { Text("Auto-crop scanner", color = Color.White) }
            IconButton({ flash = !flash }) {
                Icon(if (flash) Icons.Filled.FlashOn else Icons.Filled.FlashOff, "Flash", tint = Color.White)
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xAA000000))
            .navigationBarsPadding().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (mode == CamMode.ID) Text(if (shots.isEmpty()) "ID Card: capture the FRONT side" else "Now capture the BACK side",
                color = Color.White, fontSize = 15.sp)
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Single", !batch) { onBatch(false) }
                Pill("Batch", batch) { onBatch(true) }
            }
            Row {
                CamMode.values().forEach { m ->
                    Text(m.label, color = if (m == mode) Cyan else Color.White,
                        fontWeight = if (m == mode) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clickable {
                            onMode(m)
                            if (m == CamMode.HAND && !aiReady)
                                Toast.makeText(ctx, "For accurate Hindi handwriting, turn on AI in Settings.", Toast.LENGTH_LONG).show()
                        }.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(72.dp).clickable { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Image, "Gallery", tint = Color.White)
                    Text("Gallery", color = Color.White, fontSize = 12.sp)
                }
                Box(Modifier.size(84.dp).border(4.dp, Color.White, CircleShape).padding(8.dp)
                    .clip(CircleShape).background(Color.White).clickable(enabled = !busy) { shoot() })
                Box(Modifier.width(72.dp), contentAlignment = Alignment.Center) {
                    if (mode != CamMode.ID && batch && shots.isNotEmpty()) Button({ onDone(shots.toList(), mode) }) { Text("Done (${shots.size})") }
                }
            }
        }
    }
}
