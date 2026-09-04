package com.example.server

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Environment
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import kotlinx.coroutines.*
import java.io.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

class WebFileServer(
    private val context: Context,
    val port: Int = 8080,
    var rootDir: File = Environment.getExternalStorageDirectory(),
    private val onLog: (String) -> Unit = {}
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    var isRunning: Boolean = false
        private set

    fun start() {
        if (isRunning) return
        isRunning = true
        serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket(port)
                onLog("Server started on port $port")
                while (isActive && !serverSocket!!.isClosed) {
                    try {
                        val clientSocket = serverSocket!!.accept()
                        launch(Dispatchers.IO) {
                            handleClient(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (!isActive) break
                    }
                }
            } catch (e: Exception) {
                onLog("Server error: ${e.message}")
            } finally {
                isRunning = false
                onLog("Server stopped")
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        serverJob?.cancel()
        serverSocket = null
        onLog("Server stopped by user")
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val rawInput = s.getInputStream()
                val out = BufferedOutputStream(s.getOutputStream())

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val rawUri = parts[1]

                // Read headers
                val headers = mutableMapOf<String, String>()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.isEmpty()) break
                    val headerParts = line!!.split(":", limit = 2)
                    if (headerParts.size == 2) {
                        headers[headerParts[0].trim().lowercase()] = headerParts[1].trim()
                    }
                }

                val decodedPath = try {
                    URLDecoder.decode(rawUri.substringBefore("?"), "UTF-8")
                } catch (e: Exception) {
                    rawUri.substringBefore("?")
                }

                if (method.equals("GET", ignoreCase = true)) {
                    handleGet(decodedPath, out)
                } else if (method.equals("POST", ignoreCase = true)) {
                    handlePost(decodedPath, headers, reader, rawInput, out)
                } else {
                    sendResponse(out, 405, "Method Not Allowed", "text/plain", "Method not supported".toByteArray())
                }
            }
        } catch (e: Exception) {
            // connection reset or client closed
        }
    }

    private fun handleGet(path: String, out: OutputStream) {
        val cleanPath = path.removePrefix("/")
        val targetFile = if (cleanPath.isEmpty()) rootDir else File(rootDir, cleanPath)

        if (!targetFile.exists()) {
            sendResponse(out, 404, "Not Found", "text/html", "<h1>404 Not Found</h1><p>The requested path does not exist on this device.</p>".toByteArray())
            return
        }

        if (targetFile.isDirectory) {
            val html = generateDirectoryHtml(targetFile)
            sendResponse(out, 200, "OK", "text/html; charset=UTF-8", html.toByteArray(Charsets.UTF_8))
        } else {
            // Serve file for download or streaming
            val mime = getMimeType(targetFile)
            val fileLength = targetFile.length()

            val header = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: $mime\r\n")
                append("Content-Length: $fileLength\r\n")
                append("Content-Disposition: inline; filename=\"${targetFile.name}\"\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(header.toByteArray(Charsets.UTF_8))

            targetFile.inputStream().use { fis ->
                val buffer = ByteArray(32768)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                }
            }
            out.flush()
            onLog("Downloaded: ${targetFile.name} (${Formatter.formatFileSize(context, fileLength)})")
        }
    }

    private fun handlePost(
        path: String,
        headers: Map<String, String>,
        reader: BufferedReader,
        rawInput: InputStream,
        out: OutputStream
    ) {
        val targetDir = if (path.isEmpty() || path == "/") rootDir else File(rootDir, path.removePrefix("/"))
        if (!targetDir.exists() || !targetDir.isDirectory) {
            sendResponse(out, 400, "Bad Request", "text/html", "Target directory does not exist".toByteArray())
            return
        }

        val contentType = headers["content-type"] ?: ""
        if (contentType.contains("multipart/form-data")) {
            val boundary = contentType.substringAfter("boundary=").trim()
            try {
                // Save uploaded file
                val uploaded = parseMultipartAndSave(rawInput, boundary, targetDir, headers["content-length"]?.toLongOrNull() ?: 0L)
                if (uploaded) {
                    onLog("File uploaded to ${targetDir.name}")
                    sendRedirect(out, path)
                } else {
                    sendResponse(out, 400, "Bad Request", "text/html", "Upload parsing failed".toByteArray())
                }
            } catch (e: Exception) {
                sendResponse(out, 500, "Internal Server Error", "text/html", "Upload error: ${e.message}".toByteArray())
            }
        } else {
            sendResponse(out, 400, "Bad Request", "text/html", "Unsupported POST type".toByteArray())
        }
    }

    private fun parseMultipartAndSave(
        input: InputStream,
        boundary: String,
        targetDir: File,
        contentLength: Long
    ): Boolean {
        // Stream multipart reader
        val boundaryBytes = ("--$boundary").toByteArray(Charsets.ISO_8859_1)
        val endBoundaryBytes = ("--$boundary--").toByteArray(Charsets.ISO_8859_1)

        val bis = BufferedInputStream(input)
        val headerBuffer = ByteArrayOutputStream()
        var b: Int
        var inHeader = true
        var filename = "uploaded_file_${System.currentTimeMillis()}"

        // Read until first empty line after boundary
        val lineBuffer = ByteArrayOutputStream()
        while (bis.read().also { b = it } != -1) {
            lineBuffer.write(b)
            val str = lineBuffer.toString("ISO-8859-1")
            if (str.endsWith("\r\n")) {
                val line = str.trim()
                if (line.contains("filename=\"")) {
                    filename = line.substringAfter("filename=\"").substringBefore("\"")
                }
                if (line.isEmpty()) {
                    // Header finished, data follows
                    break
                }
                lineBuffer.reset()
            }
        }

        if (filename.isBlank()) filename = "uploaded_file_${System.currentTimeMillis()}"
        val destFile = File(targetDir, filename)

        // Stream file content until boundary
        FileOutputStream(destFile).use { fos ->
            val buf = ByteArray(16384)
            var read: Int
            // Read until end
            while (bis.read(buf).also { read = it } != -1) {
                fos.write(buf, 0, read)
            }
        }
        return true
    }

    private fun generateDirectoryHtml(dir: File): String {
        val relPath = dir.absolutePath.removePrefix(rootDir.absolutePath).removePrefix("/")
        val items = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyArray()

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        val parentHref = if (dir.absolutePath == rootDir.absolutePath) null else {
            val parentRel = dir.parentFile?.absolutePath?.removePrefix(rootDir.absolutePath)?.removePrefix("/") ?: ""
            "/$parentRel"
        }

        return buildString {
            append("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>DevVault - ${dir.name.ifEmpty { "Root" }}</title>
                    <style>
                        :root {
                            --bg: #0f172a;
                            --card: #1e293b;
                            --text: #f8fafc;
                            --accent: #38bdf8;
                            --hover: #334155;
                            --border: #475569;
                        }
                        body {
                            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                            background: var(--bg);
                            color: var(--text);
                            margin: 0;
                            padding: 20px;
                        }
                        .container {
                            max-width: 1000px;
                            margin: 0 auto;
                        }
                        header {
                            display: flex;
                            align-items: center;
                            justify-content: space-between;
                            border-bottom: 1px solid var(--border);
                            padding-bottom: 16px;
                            margin-bottom: 20px;
                        }
                        h1 {
                            margin: 0;
                            font-size: 24px;
                            color: var(--accent);
                        }
                        .path-bar {
                            background: var(--card);
                            padding: 10px 16px;
                            border-radius: 8px;
                            margin-bottom: 20px;
                            font-family: monospace;
                            word-break: break-all;
                        }
                        .upload-box {
                            background: var(--card);
                            padding: 16px;
                            border-radius: 8px;
                            margin-bottom: 20px;
                            border: 1px dashed var(--accent);
                        }
                        .upload-box form {
                            display: flex;
                            gap: 12px;
                            align-items: center;
                            flex-wrap: wrap;
                        }
                        input[type="file"] {
                            color: var(--text);
                        }
                        button, input[type="submit"] {
                            background: var(--accent);
                            color: #0f172a;
                            border: none;
                            padding: 8px 16px;
                            border-radius: 6px;
                            font-weight: 600;
                            cursor: pointer;
                        }
                        button:hover, input[type="submit"]:hover {
                            opacity: 0.9;
                        }
                        table {
                            width: 100%;
                            border-collapse: collapse;
                            background: var(--card);
                            border-radius: 8px;
                            overflow: hidden;
                        }
                        th, td {
                            padding: 12px 16px;
                            text-align: left;
                            border-bottom: 1px solid var(--border);
                        }
                        th {
                            background: #243247;
                            font-size: 14px;
                            color: #94a3b8;
                        }
                        tr:hover {
                            background: var(--hover);
                        }
                        a {
                            color: var(--text);
                            text-decoration: none;
                            display: flex;
                            align-items: center;
                            gap: 8px;
                        }
                        a:hover {
                            color: var(--accent);
                        }
                        .icon {
                            font-size: 18px;
                        }
                        .meta {
                            font-size: 13px;
                            color: #94a3b8;
                        }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <header>
                            <h1>⚡ DevVault Web File Share</h1>
                            <span class="meta">${items.size} items</span>
                        </header>
                        
                        <div class="path-bar">
                            📁 /${relPath}
                        </div>

                        <div class="upload-box">
                            <strong>⬆️ Upload File to this Directory</strong>
                            <form action="/${relPath}" method="POST" enctype="multipart/form-data" style="margin-top: 10px;">
                                <input type="file" name="file" required>
                                <input type="submit" value="Upload to Phone">
                            </form>
                        </div>

                        <table>
                            <thead>
                                <tr>
                                    <th>Name</th>
                                    <th>Size</th>
                                    <th>Date Modified</th>
                                    <th>Action</th>
                                </tr>
                            </thead>
                            <tbody>
            """.trimIndent())

            if (parentHref != null) {
                append("""
                    <tr>
                        <td><a href="$parentHref"><span class="icon">⬆️</span> .. [Parent Directory]</a></td>
                        <td>-</td>
                        <td>-</td>
                        <td>-</td>
                    </tr>
                """.trimIndent())
            }

            items.forEach { file ->
                val fileHref = "/" + file.absolutePath.removePrefix(rootDir.absolutePath).removePrefix("/")
                val icon = if (file.isDirectory) "📁" else when (file.extension.lowercase()) {
                    "jpg", "jpeg", "png", "gif", "webp" -> "🖼️"
                    "mp4", "mkv", "avi" -> "🎬"
                    "mp3", "wav", "m4a" -> "🎵"
                    "zip", "tar", "gz", "7z", "rar" -> "📦"
                    "apk" -> "🤖"
                    "pdf" -> "📄"
                    "kt", "java", "py", "js", "html", "css", "json" -> "💻"
                    else -> "📄"
                }

                val sizeStr = if (file.isDirectory) "-" else Formatter.formatFileSize(context, file.length())
                val dateStr = sdf.format(Date(file.lastModified()))

                append("""
                    <tr>
                        <td><a href="$fileHref"><span class="icon">$icon</span> ${file.name}</a></td>
                        <td class="meta">$sizeStr</td>
                        <td class="meta">$dateStr</td>
                        <td>
                            <a href="$fileHref" download="${file.name}" style="color: var(--accent); font-size: 13px;">${if (file.isDirectory) "Open ➔" else "Download ⬇️"}</a>
                        </td>
                    </tr>
                """.trimIndent())
            }

            append("""
                            </tbody>
                        </table>
                    </div>
                </body>
                </html>
            """.trimIndent())
        }
    }

    private fun sendResponse(out: OutputStream, code: Int, status: String, contentType: String, content: ByteArray) {
        val header = buildString {
            append("HTTP/1.1 $code $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${content.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(content)
        out.flush()
    }

    private fun sendRedirect(out: OutputStream, location: String) {
        val target = if (location.isEmpty()) "/" else location
        val header = buildString {
            append("HTTP/1.1 303 See Other\r\n")
            append("Location: $target\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun getMimeType(file: File): String {
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "json" -> "application/json"
            "kt", "java", "c", "cpp", "h", "py", "sh" -> "text/plain"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    companion object {
        fun getLocalIpAddress(): String? {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                for (intf in Collections.list(interfaces)) {
                    if (intf.isLoopback || !intf.isUp) continue
                    for (addr in Collections.list(intf.inetAddresses)) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress
                        }
                    }
                }
            } catch (e: Exception) {
                // ignore
            }
            return null
        }
    }
}
