package com.example.ui.screens.extractor

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isExtracting by remember { mutableStateOf(false) }
    var extractionProgress by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isExtracting = true
                try {
                    val result = extractTextFromFile(context, uri) { progress ->
                        extractionProgress = progress
                    }
                    if (result != null) {
                        extractionProgress = "Saving PDF..."
                        val pdfUri = saveAsPdf(context, result)
                        if (pdfUri != null) {
                            Toast.makeText(context, "PDF saved to Downloads", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Failed to save PDF", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(context, "Could not extract text", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    isExtracting = false
                    extractionProgress = ""
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Text Extractor") })
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Extract text from ANY file\n(Images, TXT, code files)",
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(32.dp))
                if (isExtracting) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(extractionProgress, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Button(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                        Text("Select File to Extract")
                    }
                }
            }
        }
    }
}

private suspend fun extractTextFromFile(context: Context, uri: Uri, updateProgress: (String) -> Unit): String? {
    return withContext(Dispatchers.IO) {
        val type = context.contentResolver.getType(uri)
        val name = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name ?: "unknown"
        
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val header = "File Name: $name\nFile Type: ${type ?: "Unknown"}\nDate: $date\n\n--- EXTRACTED CONTENT ---\n\n"

        if (type != null && type.startsWith("image/")) {
            updateProgress("Running ML Kit OCR...")
            val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            if (bitmap != null) {
                try {
                    val image = InputImage.fromBitmap(bitmap, 0)
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    val result = recognizer.process(image).await()
                    return@withContext header + (result.text.ifEmpty { "No text found in image." })
                } catch (e: Exception) {
                    return@withContext header + "Failed to run OCR: ${e.message}"
                }
            }
        }
        
        if (type == "application/pdf" || name.lowercase().endsWith(".pdf")) {
            updateProgress("Extracting PDF text...")
            try {
                com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext)
                val pdDocument = com.tom_roush.pdfbox.pdmodel.PDDocument.load(context.contentResolver.openInputStream(uri))
                val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                val extractedText = stripper.getText(pdDocument)
                pdDocument.close()
                return@withContext header + (if (extractedText.isNullOrEmpty()) "No text found in PDF." else extractedText)
            } catch (e: Exception) {
                return@withContext header + "Failed to extract PDF: ${e.message}"
            }
        }

        if (name.lowercase().endsWith(".docx")) {
            updateProgress("Extracting DOCX text...")
            try {
                var text = ""
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    java.util.zip.ZipInputStream(inputStream).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (entry.name == "word/document.xml") {
                                val xmlString = String(zis.readBytes())
                                // Very basic XML tag stripping for DOCX text
                                text = xmlString.replace(Regex("<w:p[^>]*>"), "\n")
                                    .replace(Regex("<[^>]+>"), "")
                                    .replace("&lt;", "<")
                                    .replace("&gt;", ">")
                                    .replace("&amp;", "&")
                                    .trim()
                                break
                            }
                            entry = zis.nextEntry
                        }
                    }
                }
                return@withContext header + (if (text.isNullOrEmpty()) "No text found in DOCX." else text)
            } catch (e: Exception) {
                return@withContext header + "Failed to extract DOCX: ${e.message}"
            }
        }
        
        // For text, source code, and anything else, try to read as text.
        updateProgress("Reading file...")
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) {
                // Check if file is largely unprintable (binary)
                var unprintableCount = 0
                val maxCheck = minOf(bytes.size, 1024)
                for (i in 0 until maxCheck) {
                    val b = bytes[i]
                    if (b < 0x09 || (b in 0x0E..0x1F) || b == 0x7F.toByte()) {
                        unprintableCount++
                    }
                }
                val isBinary = unprintableCount > (maxCheck * 0.1)

                if (isBinary) {
                    updateProgress("Generating Hex Dump for binary file...")
                    val sb = StringBuilder()
                    val limit = minOf(bytes.size, 50000)
                    for (i in 0 until limit step 16) {
                        sb.append(String.format("%08X  ", i))
                        for (j in 0 until 16) {
                            if (i + j < limit) {
                                sb.append(String.format("%02X ", bytes[i + j]))
                            } else sb.append("   ")
                        }
                        sb.append(" |")
                        for (j in 0 until 16) {
                            if (i + j < limit) {
                                val c = bytes[i + j].toInt().toChar()
                                val isPrintable = c in ' '..'~'
                                sb.append(if (isPrintable) c else '.')
                            }
                        }
                        sb.append("\n")
                    }
                    if (bytes.size > limit) sb.append("\n[TRUNCATED due to size]")
                    return@withContext header + sb.toString()
                } else {
                    val string = String(bytes)
                    if (string.length > 50000) {
                        return@withContext header + string.substring(0, 50000) + "\n\n[TRUNCATED due to size]"
                    }
                    return@withContext header + string
                }
            }
        } catch (e: Exception) {
            return@withContext header + "Failed to read file: ${e.message}"
        }
        null
    }
}

private suspend fun saveAsPdf(context: Context, text: String): Uri? {
    return withContext(Dispatchers.IO) {
        try {
            val pdfDocument = PdfDocument()
            val paint = android.graphics.Paint()
            paint.textSize = 12f
            
            val lines = text.split("\n")
            val pageHeight = 842 // A4
            val pageWidth = 595
            
            var yPosition = 40f
            var pageNum = 1
            var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
            var page = pdfDocument.startPage(pageInfo)
            
            for (line in lines) {
                // very simple text wrap (not accurate for long continuous strings, but good enough for code/text)
                var currentLine = line
                while (currentLine.length > 90) {
                    val chunk = currentLine.substring(0, 90)
                    currentLine = currentLine.substring(90)
                    page.canvas.drawText(chunk, 40f, yPosition, paint)
                    yPosition += 16f
                    if (yPosition > pageHeight - 40f) {
                        pdfDocument.finishPage(page)
                        pageNum++
                        pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
                        page = pdfDocument.startPage(pageInfo)
                        yPosition = 40f
                    }
                }
                page.canvas.drawText(currentLine, 40f, yPosition, paint)
                yPosition += 16f
                if (yPosition > pageHeight - 40f) {
                    pdfDocument.finishPage(page)
                    pageNum++
                    pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
                    page = pdfDocument.startPage(pageInfo)
                    yPosition = 40f
                }
            }
            pdfDocument.finishPage(page)

            val fileName = "Extracted_${System.currentTimeMillis()}.pdf"
            val resolver = context.contentResolver
            
            var outUri: Uri? = null
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DevVault")
                }
                outUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (outUri != null) {
                    resolver.openOutputStream(outUri)?.use { pdfDocument.writeTo(it) }
                }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DevVault")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                file.outputStream().use { pdfDocument.writeTo(it) }
                outUri = Uri.fromFile(file)
            }
            pdfDocument.close()
            outUri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
