package com.example.api

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.NetworkInterface
import java.net.URLDecoder
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors

class ApiServer(
    private val port: Int,
    private val listener: ApiServerListener
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val serverExecutor = Executors.newSingleThreadExecutor()
    private val handlerExecutor = Executors.newCachedThreadPool()

    interface ApiServerListener {
        fun onPlay()
        fun onPause()
        fun onStop()
        fun onVolumeSet(volume: Int)
        fun onPlayMedia(url: String, title: String?, subtitle: String?)
        fun onTextToSpeech(text: String)
        fun onOpenApp(packageName: String)
        fun getPlayerStatus(): PlayerStatus
    }

    data class PlayerStatus(
        val state: String,       // "playing", "paused", "idle"
        val volume: Int,          // 0 to 100
        val mediaTitle: String,
        val mediaSubtitle: String,
        val mediaDuration: Int,   // seconds
        val mediaPosition: Int,   // seconds
        val deviceName: String
    )

    fun start() {
        if (isRunning) return
        isRunning = true
        serverExecutor.execute {
            try {
                serverSocket = ServerSocket(port)
                Log.d("ApiServer", "HTTP API Server started on port $port")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    handlerExecutor.execute {
                        handleConnection(socket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e("ApiServer", "Error starting API server on port $port", e)
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (e: Exception) {
            Log.e("ApiServer", "Error closing server socket", e)
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val firstLine = reader.readLine()
            if (firstLine.isNullOrEmpty()) {
                socket.close()
                return
            }

            val parts = firstLine.split(" ")
            if (parts.size < 2) {
                socket.close()
                return
            }

            val method = parts[0]
            val fullPath = parts[1]

            if (method.equals("OPTIONS", ignoreCase = true)) {
                sendResponse(socket, 204, "", "text/plain")
                return
            }

            val questionIdx = fullPath.indexOf('?')
            val path = if (questionIdx >= 0) fullPath.substring(0, questionIdx) else fullPath
            val queryString = if (questionIdx >= 0) fullPath.substring(questionIdx + 1) else ""

            var contentLength = 0
            var contentType = ""
            var line: String?
            while (true) {
                line = reader.readLine()
                if (line.isNullOrEmpty()) {
                    break
                }
                val lowerLine = line.lowercase(Locale.US)
                if (lowerLine.startsWith("content-length:")) {
                    contentLength = line.substring("content-length:".length).trim().toIntOrNull() ?: 0
                } else if (lowerLine.startsWith("content-type:")) {
                    contentType = line.substring("content-type:".length).trim()
                }
            }

            val body = if (contentLength > 0) {
                val charBuffer = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val result = reader.read(charBuffer, read, contentLength - read)
                    if (result == -1) break
                    read += result
                }
                String(charBuffer, 0, read)
            } else {
                ""
            }

            val params = mutableMapOf<String, String>()
            if (queryString.isNotEmpty()) {
                params.putAll(parseQuery(queryString))
            }

            if (method.equals("POST", ignoreCase = true)) {
                if (contentType.contains("application/x-www-form-urlencoded")) {
                    params.putAll(parseQuery(body))
                } else if (contentType.contains("application/json")) {
                    try {
                        val urlMatch = "\"url\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(body)
                        val titleMatch = "\"title\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(body)
                        val textMatch = "\"text\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(body)
                        val packMatch = "\"package\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(body)
                        val volumeMatch = "\"volume\"\\s*:\\s*([0-9]+)".toRegex().find(body)

                        urlMatch?.let { params["url"] = it.groupValues[1] }
                        titleMatch?.let { params["title"] = it.groupValues[1] }
                        textMatch?.let { params["text"] = it.groupValues[1] }
                        packMatch?.let { params["package"] = it.groupValues[1] }
                        volumeMatch?.let { params["volume"] = it.groupValues[1] }
                    } catch (e: Exception) {
                        Log.e("ApiServer", "JSON parse error", e)
                    }
                }
            }

            routeRequest(socket, path, params)
        } catch (e: Exception) {
            Log.e("ApiServer", "Error handling connection", e)
            try {
                socket.close()
            } catch (ex: Exception) {}
        }
    }

    private fun routeRequest(socket: Socket, path: String, params: Map<String, String>) {
        try {
            when (path) {
                "/api/status" -> {
                    val status = listener.getPlayerStatus()
                    val json = """
                        {
                          "state": "${status.state}",
                          "volume": ${status.volume},
                          "media_title": "${escapeJson(status.mediaTitle)}",
                          "media_subtitle": "${escapeJson(status.mediaSubtitle)}",
                          "media_duration": ${status.mediaDuration},
                          "media_position": ${status.mediaPosition},
                          "device_name": "${escapeJson(status.deviceName)}"
                        }
                    """.trimIndent()
                    sendResponse(socket, 200, json)
                }
                "/api/play" -> {
                    listener.onPlay()
                    sendResponse(socket, 200, "{\"success\": true, \"action\": \"play\"}")
                }
                "/api/pause" -> {
                    listener.onPause()
                    sendResponse(socket, 200, "{\"success\": true, \"action\": \"pause\"}")
                }
                "/api/stop" -> {
                    listener.onStop()
                    sendResponse(socket, 200, "{\"success\": true, \"action\": \"stop\"}")
                }
                "/api/volume" -> {
                    val volumeStr = params["volume"] ?: params["level"]
                    if (volumeStr != null) {
                        val volume = volumeStr.toIntOrNull()?.coerceIn(0, 100)
                        if (volume != null) {
                            listener.onVolumeSet(volume)
                            sendResponse(socket, 200, "{\"success\": true, \"volume\": $volume}")
                            return
                        }
                    }
                    sendResponse(socket, 400, "{\"error\": \"Missing or invalid volume parameter (0 to 100)\"}")
                }
                "/api/play_url" -> {
                    val url = params["url"] ?: params["media_id"]
                    if (!url.isNullOrEmpty()) {
                        val title = params["title"] ?: "Streaming Audio/Video"
                        val subtitle = params["artist"] ?: params["subtitle"] ?: "Home Assistant Stream"
                        listener.onPlayMedia(url, title, subtitle)
                        sendResponse(socket, 200, "{\"success\": true, \"url\": \"${escapeJson(url)}\"}")
                        return
                    }
                    sendResponse(socket, 400, "{\"error\": \"Missing 'url' parameter\"}")
                }
                "/api/say" -> {
                    val text = params["text"] ?: params["message"]
                    if (!text.isNullOrEmpty()) {
                        listener.onTextToSpeech(text)
                        sendResponse(socket, 200, "{\"success\": true, \"speech\": \"${escapeJson(text)}\"}")
                        return
                    }
                    sendResponse(socket, 400, "{\"error\": \"Missing 'text' parameter\"}")
                }
                "/api/open_app" -> {
                    val pkg = params["package"] ?: params["app"]
                    if (!pkg.isNullOrEmpty()) {
                        listener.onOpenApp(pkg)
                        sendResponse(socket, 200, "{\"success\": true, \"launched\": \"${escapeJson(pkg)}\"}")
                        return
                    }
                    sendResponse(socket, 400, "{\"error\": \"Missing 'package' parameter\"}")
                }
                "/" -> {
                    val html = """
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <title>${listener.getPlayerStatus().deviceName}</title>
                            <style>
                                body { font-family: -apple-system, sans-serif; background-color: #121212; color: #E0E0E0; text-align: center; padding: 50px; }
                                h1 { color: #2196F3; }
                                code { background-color: #222; padding: 5px 10px; border-radius: 4px; color: #4CAF50; font-family: monospace; }
                                .desc { margin-bottom: 30px; }
                                .endpoints { max-width: 600px; margin: 0 auto; text-align: left; background: #1e1e1e; padding: 20px; border-radius: 8px; border: 1px solid #333; }
                                .m-val { color: #FF9800; }
                            </style>
                        </head>
                        <body>
                            <h1>${listener.getPlayerStatus().deviceName} API Active</h1>
                            <p class="desc">Active and waiting for Home Assistant commands on port <code>$port</code></p>
                            <div class="endpoints">
                                <h3>Available Endpoints:</h3>
                                <ul>
                                    <li><strong>GET</strong> <code>/api/status</code> - Current player status</li>
                                    <li><strong>POST/GET</strong> <code>/api/play</code> - Resume playback</li>
                                    <li><strong>POST/GET</strong> <code>/api/pause</code> - Pause playback</li>
                                    <li><strong>POST/GET</strong> <code>/api/stop</code> - Stop playback</li>
                                    <li><strong>POST/GET</strong> <code>/api/volume?volume=50</code> - Apply volume (0-100)</li>
                                    <li><strong>POST/GET</strong> <code>/api/play_url?url=&lt;url&gt;&title=&lt;title&gt;</code> - Stream Url</li>
                                    <li><strong>POST/GET</strong> <code>/api/say?text=&lt;text&gt;</code> - Text-To-Speech announcement</li>
                                    <li><strong>POST/GET</strong> <code>/api/open_app?package=&lt;pkg&gt;</code> - Play another app via package info</li>
                                </ul>
                            </div>
                        </body>
                        </html>
                    """.trimIndent()
                    sendResponse(socket, 200, html, "text/html")
                }
                else -> {
                    sendResponse(socket, 404, "{\"error\": \"Endpoint not found\"}")
                }
            }
        } catch (e: Exception) {
            Log.e("ApiServer", "Error routing request", e)
            sendResponse(socket, 500, "{\"error\": \"${e.localizedMessage}\"}")
        }
    }

    private fun sendResponse(
        socket: Socket,
        code: Int,
        body: String,
        contentType: String = "application/json"
    ) {
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val statusText = when (code) {
                200 -> "OK"
                204 -> "No Content"
                400 -> "Bad Request"
                404 -> "Not Found"
                500 -> "Internal Server Error"
                else -> "OK"
            }
            val os = socket.getOutputStream()
            val writer = PrintWriter(OutputStreamWriter(os, Charsets.UTF_8))
            writer.print("HTTP/1.1 $code $statusText\r\n")
            writer.print("Access-Control-Allow-Origin: *\r\n")
            writer.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            writer.print("Access-Control-Allow-Headers: Content-Type\r\n")
            writer.print("Content-Type: $contentType; charset=UTF-8\r\n")
            writer.print("Content-Length: ${bytes.size}\r\n")
            writer.print("Connection: close\r\n")
            writer.print("\r\n")
            writer.flush()

            os.write(bytes)
            os.flush()
        } catch (e: Exception) {
            Log.e("ApiServer", "Error sending response", e)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {}
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val pairs = query.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                try {
                    val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                    val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                    params[key] = value
                } catch (e: Exception) {
                    Log.e("ApiServer", "Error decoding query param", e)
                }
            }
        }
        return params
    }

    companion object {
        fun getLocalIpAddress(): String {
            try {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                for (intf in interfaces) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress) {
                            val host = addr.hostAddress ?: ""
                            val isIPv4 = host.indexOf(':') < 0
                            if (isIPv4) return host
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ApiServer", "IP retrieval failed", e)
            }
            return "127.0.0.1"
        }

        private fun escapeJson(str: String): String {
            return str.replace("\\", "\\\\")
                      .replace("\"", "\\\"")
                      .replace("\n", "\\n")
                      .replace("\r", "\\r")
                      .replace("\t", "\\t")
        }
    }
}
