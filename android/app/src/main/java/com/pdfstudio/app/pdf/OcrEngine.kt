package com.pdfstudio.app.pdf

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One recognised line of text; coordinates are pixels in the bitmap that was recognised (origin top-left). */
data class OcrLine(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

/** On-device OCR using ML Kit's bundled Latin model, so it works offline and sends nothing off the phone. */
class OcrEngine : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap): List<OcrLine> = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                cont.resume(
                    result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                        line.boundingBox?.let {
                            OcrLine(line.text, it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
                        }
                    }
                )
            }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    override fun close() = recognizer.close()
}
