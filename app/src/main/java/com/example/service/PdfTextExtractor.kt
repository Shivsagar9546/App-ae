package com.example.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object PdfTextExtractor {

    suspend fun extractPdfInfo(context: Context, uri: Uri): PdfExtractResult = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val inputStream = contentResolver.openInputStream(uri) ?: return@withContext PdfExtractResult.Error("Could not open PDF file")

            // Copy to temp file for PdfRenderer
            val tempFile = File(context.cacheDir, "temp_pdf_${System.currentTimeMillis()}.pdf")
            FileOutputStream(tempFile).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()

            val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val pageCount = renderer.pageCount

            if (pageCount == 0) {
                renderer.close()
                pfd.close()
                tempFile.delete()
                return@withContext PdfExtractResult.Error("PDF is empty")
            }

            // Render first page to compact bitmap for AI vision analysis
            val page = renderer.openPage(0)
            val width = 1000
            val height = (page.height * (1000f / page.width)).toInt().coerceAtMost(1600)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

            renderer.close()
            pfd.close()
            tempFile.delete()

            PdfExtractResult.Success(
                pageCount = pageCount,
                firstPageBitmap = bitmap,
                firstPageBase64 = base64
            )
        } catch (e: Exception) {
            PdfExtractResult.Error(e.message ?: "Failed to process PDF file")
        }
    }

    sealed class PdfExtractResult {
        data class Success(
            val pageCount: Int,
            val firstPageBitmap: Bitmap?,
            val firstPageBase64: String?
        ) : PdfExtractResult()

        data class Error(val message: String) : PdfExtractResult()
    }
}
