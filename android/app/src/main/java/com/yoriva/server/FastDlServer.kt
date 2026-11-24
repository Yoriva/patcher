package com.yoriva.server

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class FastDlState(
    val isRunning: Boolean = false,
    val ipAddress: String = "127.0.0.1",
    val port: Int = 8088,
    val cstrikeRoot: String = "",
    val totalDownloads: Int = 0,
    val bytesTransferred: Long = 0,
    val lastRequestedFile: String = "",
) {
    val downloadUrl: String get() = "http://$ipAddress:$port/"
    val cvarCommand: String get() = "sv_downloadurl \"$downloadUrl\"; sv_allowdownload 1"
}

/**
 * Embedded FastDL HTTP/1.1 file server for CS 1.6 / Xash3D.
 * Written with pure Android-compatible java.net sockets (zero external dependencies).
 * Supports HTTP/1.1, Accept-Ranges, Content-Length, and Range requests (206 Partial Content).
 */
class FastDlServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val installPathProvider: () -> String,
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val workerExecutor = Executors.newCachedThreadPool()
    private val totalDownloads = AtomicInteger(0)
    private val totalBytes = AtomicLong(0)

    private val _state = MutableStateFlow(FastDlState())
    val state: StateFlow<FastDlState> = _state.asStateFlow()

    fun toggleServer(port: Int = 8088) {
        if (_state.value.isRunning) {
            stopServer()
        } else {
            startServer(port)
        }
    }

    fun startServer(port: Int = 8088) {
        if (_state.value.isRunning) return
        val gameDir = File(installPathProvider())
        val cstrikeDir = if (gameDir.name == "cstrike") gameDir else File(gameDir, "cstrike")
        val localIp = getDeviceIpAddress()

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                val ss = ServerSocket(port)
                serverSocket = ss
                _state.value = FastDlState(
                    isRunning = true,
                    ipAddress = localIp,
                    port = port,
                    cstrikeRoot = cstrikeDir.absolutePath,
                    totalDownloads = totalDownloads.get(),
                    bytesTransferred = totalBytes.get(),
                )

                while (isActive && !ss.isClosed) {
                    try {
                        val clientSocket = ss.accept()
                        workerExecutor.execute {
                            handleClient(clientSocket, cstrikeDir)
                        }
                    } catch (e: SocketException) {
                        break // Server closed
                    } catch (t: Throwable) {
                        // Accept error
                    }
                }
            } catch (t: Throwable) {
                // Bind error or failed to start
            } finally {
                _state.value = _state.value.copy(isRunning = false)
            }
        }
    }

    fun stopServer() {
        scope.launch(Dispatchers.IO) {
            try {
                serverSocket?.close()
                serverSocket = null
                serverJob?.cancel()
                serverJob = null
            } catch (_: Throwable) {}
            _state.value = _state.value.copy(isRunning = false)
        }
    }

    private fun handleClient(socket: Socket, baseDir: File) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val out = s.getOutputStream()

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0]
                var rawPath = parts[1]
                if (rawPath.startsWith("/")) rawPath = rawPath.substring(1)

                // Read headers (find Range header if requested)
                var rangeHeader: String? = null
                var line: String?
                while (!reader.readLine().also { line = it }.isNullOrEmpty()) {
                    val h = line ?: break
                    if (h.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = h.substring(6).trim()
                    }
                }

                // Security check
                if (rawPath.contains("..") || rawPath.contains("//")) {
                    sendHttpError(out, 403, "Forbidden")
                    return
                }

                val targetFile = File(baseDir, rawPath)
                if (!targetFile.exists() || !targetFile.isFile) {
                    sendHttpError(out, 404, "Not Found: $rawPath")
                    return
                }

                val fileLength = targetFile.length()
                val mimeType = getMimeType(targetFile.extension.lowercase())

                var startByte = 0L
                var endByte = fileLength - 1
                val isRange = rangeHeader != null && rangeHeader.startsWith("bytes=")

                if (isRange) {
                    try {
                        val rangeSpec = rangeHeader!!.substring(6).trim()
                        val rangeParts = rangeSpec.split("-")
                        if (rangeParts[0].isNotEmpty()) {
                            startByte = rangeParts[0].toLong()
                        }
                        if (rangeParts.size > 1 && rangeParts[1].isNotEmpty()) {
                            endByte = rangeParts[1].toLong()
                        }
                    } catch (_: Exception) {}
                }

                if (startByte > endByte || startByte >= fileLength) {
                    sendHttpError(out, 416, "Requested Range Not Satisfiable")
                    return
                }

                val contentLength = endByte - startByte + 1
                val statusCode = if (isRange) "206 Partial Content" else "200 OK"

                val responseHeaders = buildString {
                    append("HTTP/1.1 $statusCode\r\n")
                    append("Content-Type: $mimeType\r\n")
                    append("Content-Length: $contentLength\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    if (isRange) {
                        append("Content-Range: bytes $startByte-$endByte/$fileLength\r\n")
                    }
                    append("Connection: close\r\n")
                    append("\r\n")
                }

                out.write(responseHeaders.toByteArray(Charsets.ISO_8859_1))
                out.flush()

                if (method.equals("HEAD", ignoreCase = true)) {
                    return
                }

                totalDownloads.incrementAndGet()
                _state.value = _state.value.copy(
                    totalDownloads = totalDownloads.get(),
                    lastRequestedFile = rawPath,
                )

                var bytesWritten = 0L
                FileInputStream(targetFile).use { fis ->
                    if (startByte > 0) {
                        fis.skip(startByte)
                    }
                    val buffer = ByteArray(32768)
                    var remaining = contentLength
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = fis.read(buffer, 0, toRead)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        bytesWritten += read
                        remaining -= read
                    }
                    out.flush()
                }

                val currentTotal = totalBytes.addAndGet(bytesWritten)
                _state.value = _state.value.copy(bytesTransferred = currentTotal)
            }
        } catch (_: Throwable) {
            // Client disconnected or socket closed
        }
    }

    private fun sendHttpError(out: OutputStream, code: Int, message: String) {
        val body = "<html><body><h1>$code $message</h1></body></html>"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val response = "HTTP/1.1 $code $message\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(response.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun getMimeType(ext: String): String = when (ext) {
        "bsp" -> "application/octet-stream"
        "mdl" -> "application/octet-stream"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "spr" -> "application/octet-stream"
        "tga" -> "image/x-tga"
        "wad" -> "application/octet-stream"
        "txt" -> "text/plain"
        "res" -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun getDeviceIpAddress(): String {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val wifiInfo = wifiManager?.connectionInfo
                val ipInt = wifiInfo?.ipAddress ?: 0
                if (ipInt != 0) {
                    return String.format(
                        "%d.%d.%d.%d",
                        ipInt and 0xff,
                        ipInt shr 8 and 0xff,
                        ipInt shr 16 and 0xff,
                        ipInt shr 24 and 0xff
                    )
                }
            }
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is InetAddress && addr.hostAddress?.indexOf(':') == -1) {
                        val host = addr.hostAddress
                        if (!host.isNullOrBlank()) return host
                    }
                }
            }
        } catch (_: Throwable) {}
        return "127.0.0.1"
    }
}
