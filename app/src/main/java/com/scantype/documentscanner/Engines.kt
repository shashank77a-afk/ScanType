package com.scantype.documentscanner

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Pluggable recognition engine. Add a backend-based engine by implementing this. */
interface OcrEngine {
    val requiresInternet: Boolean
    suspend fun recognize(ctx: Context, uri: Uri): Result<String>
}

/** On-device printed Hindi (Devanagari) + English OCR. Offline. */
class MlKitOcrEngine : OcrEngine {
    override val requiresInternet = false
    private val rec by lazy { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) }
    override suspend fun recognize(ctx: Context, uri: Uri): Result<String> = runCatching {
        val img = withContext(Dispatchers.IO) { InputImage.fromFilePath(ctx, uri) }
        rec.process(img).await().text
    }
}

object Engines {
    val printed: OcrEngine = MlKitOcrEngine()
    /**
     * HANDWRITING SERVICE. Currently the on-device engine (best effort, weak on handwriting).
     * For real Hindi handwriting accuracy, implement OcrEngine against YOUR secure backend
     * (backend holds the vision-API key; the app never does) and assign it here.
     */
    val handwriting: OcrEngine = MlKitOcrEngine()
}
