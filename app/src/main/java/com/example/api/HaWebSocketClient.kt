package com.example.api

import android.util.Log
import com.example.data.model.AppConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HaWebSocketClient(
    private val config: AppConfig,
    private val deviceUuid: String,
    private val listener: HaWebSocketListener
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var reconnectTask: ScheduledFuture<*>? = null

    private var isConnectedInternal = false
    private var isConnecting = false
    private val messageIdCounter = AtomicInteger(1)

    private var wsUrl = ""
    private var httpUrl = ""

    interface HaWebSocketListener {
        fun onHaPlay()
        fun onHaPause()
        fun onHaStop()
        fun onHaPlayPause()
        fun onHaVolumeSet(volume: Int)
        fun onHaPlayMedia(url: String, title: String?, subtitle: String?)
        fun onHaTextToSpeech(text: String)
        fun onHaOpenApp(packageName: String)
        fun onHaStatusChanged(connected: Boolean, errorMsg: String? = null)
    }

    init {
        parseUrls()
    }

    private fun parseUrls() {
        val (ws, http) = getHaUrls(config.haAddress)
        wsUrl = ws
        httpUrl = http
        Log.d("HaWebSocketClient", "Parsed HA Address: Config='${config.haAddress}', WS='$wsUrl', HTTP='$httpUrl'")
    }

    fun connect() {
        if (!config.haEnabled || config.haAddress.isEmpty() || config.haToken.isEmpty()) {
            Log.d("HaWebSocketClient", "Home Assistant WebSocket connection disabled or empty settings.")
            listener.onHaStatusChanged(false, "Home Assistant WebSocket Settings are empty or inactive")
            return
        }

        executor.execute {
            if (isConnectedInternal || isConnecting) return@execute
            isConnecting = true
            Log.d("HaWebSocketClient", "Connecting to Home Assistant WebSocket: $wsUrl")

            val request = Request.Builder()
                .url(wsUrl)
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("HaWebSocketClient", "WebSocket Connection opened, waiting for auth_required...")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncomingMessage(webSocket, text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d("HaWebSocketClient", "WebSocket Connection closed: $reason ($code)")
                    handleDisconnect(reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("HaWebSocketClient", "WebSocket onFailure", t)
                    handleDisconnect(t.localizedMessage ?: "Network Failure")
                }
            })
        }
    }

    private fun handleIncomingMessage(ws: WebSocket, text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            Log.d("HaWebSocketClient", "Received message: type=$type, raw=$text")

            when (type) {
                "auth_required" -> {
                    // Send authorization details
                    val authPayload = JSONObject().apply {
                        put("type", "auth")
                        put("access_token", config.haToken)
                    }
                    ws.send(authPayload.toString())
                    Log.d("HaWebSocketClient", "Auth payload sent")
                }
                "auth_ok" -> {
                    Log.d("HaWebSocketClient", "Auth OK! Subscribing to tv_media_player_command events...")
                    isConnecting = false
                    isConnectedInternal = true
                    listener.onHaStatusChanged(true)

                    // Subscribe to tv_media_player_command custom event type
                    val subId = messageIdCounter.getAndIncrement()
                    val subscribePayload = JSONObject().apply {
                        put("id", subId)
                        put("type", "subscribe_events")
                        put("event_type", "tv_media_player_command")
                    }
                    ws.send(subscribePayload.toString())

                    // Trigger initial state sync
                    syncStateToHomeAssistant()
                }
                "auth_invalid" -> {
                    val message = json.optString("message", "Invalid Token")
                    Log.e("HaWebSocketClient", "Auth Invalid: $message")
                    handleDisconnect("Auth Invalid: $message")
                }
                "event" -> {
                    val eventObj = json.optJSONObject("event") ?: return
                    val eventType = eventObj.optString("event_type")
                    if (eventType == "tv_media_player_command") {
                        val dataObj = eventObj.optJSONObject("data") ?: return
                        val targetUuid = dataObj.optString("uuid", "")
                        val targetDeviceName = dataObj.optString("device_name", "")

                        // Match against this device's identities or check if broadcast to "all" / empty.
                        val isForMe = targetUuid.equals(deviceUuid, ignoreCase = true) ||
                                targetDeviceName.equals(config.deviceName, ignoreCase = true) ||
                                targetUuid.isEmpty() ||
                                targetUuid.equals("all", ignoreCase = true)

                        if (isForMe) {
                            val command = dataObj.optString("command", "")
                            Log.d("HaWebSocketClient", "Target matches this TV! Executing: $command")
                            when (command.lowercase()) {
                                "play" -> listener.onHaPlay()
                                "pause" -> listener.onHaPause()
                                "stop" -> listener.onHaStop()
                                "play_pause" -> listener.onHaPlayPause()
                                "volume" -> {
                                    val vol = dataObj.optInt("volume", -1)
                                    if (vol >= 0) listener.onHaVolumeSet(vol)
                                }
                                "play_url" -> {
                                    val url = dataObj.optString("url", "")
                                    if (url.isNotEmpty()) {
                                        val title = dataObj.optString("title", "Home Assistant Feed")
                                        val subtitle = dataObj.optString("subtitle", "Media Broadcast")
                                        listener.onHaPlayMedia(url, title, subtitle)
                                    }
                                }
                                "say", "tts" -> {
                                    val textMessage = dataObj.optString("text", "")
                                    if (textMessage.isNotEmpty()) {
                                        listener.onHaTextToSpeech(textMessage)
                                    }
                                }
                                "open_app" -> {
                                    val pkg = dataObj.optString("package", "")
                                    if (pkg.isNotEmpty()) {
                                        listener.onHaOpenApp(pkg)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HaWebSocketClient", "Error handling HA WS response", e)
        }
    }

    private fun handleDisconnect(error: String) {
        isConnectedInternal = false
        isConnecting = false
        listener.onHaStatusChanged(false, error)
        scheduleReconnect()
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (!config.haEnabled) return
        reconnectTask?.cancel(false)
        reconnectTask = scheduler.schedule({
            Log.d("HaWebSocketClient", "Attempting automatic reconnection to HA WebSocket...")
            connect()
        }, 10, TimeUnit.SECONDS)
    }

    fun disconnect() {
        config.copy(haEnabled = false) // virtual disable
        reconnectTask?.cancel(true)
        webSocket?.close(1000, "App closed connection")
        webSocket = null
        isConnectedInternal = false
        isConnecting = false
        Log.d("HaWebSocketClient", "HA WebSocket Disconnected manually")
    }

    fun isConnected() = isConnectedInternal

    // State Variables cached locally for REST State synchronization
    private var lastState = "idle"
    private var lastVolume = 70
    private var lastTitle = "Ambient Screen"
    private var lastSubtitle = "Waiting for Home Assistant"

    fun updateStateCache(state: String) {
        lastState = state
        syncStateToHomeAssistant()
    }

    fun updateVolumeCache(volume: Int) {
        lastVolume = volume
        syncStateToHomeAssistant()
    }

    fun updateMediaCache(title: String, subtitle: String) {
        lastTitle = title
        lastSubtitle = subtitle
        syncStateToHomeAssistant()
    }

    private fun syncStateToHomeAssistant() {
        if (!isConnectedInternal || httpUrl.isEmpty() || config.haToken.isEmpty()) return

        executor.execute {
            try {
                // Home Assistant Media Player state scheme mapping (playing, paused, idle, custom attributes)
                val entityId = "media_player.tv_media_player_${deviceUuid.lowercase()}"
                val stateUrl = "$httpUrl/api/states/$entityId"

                val attrs = JSONObject().apply {
                    put("friendly_name", config.deviceName)
                    put("volume_level", lastVolume / 100.0)
                    put("media_title", lastTitle)
                    put("media_artist", lastSubtitle)
                    put("supported_features", 21565) // Support Play, Pause, Stop, Volume Set, Play Media etc.
                }

                val payload = JSONObject().apply {
                    put("state", lastState)
                    put("attributes", attrs)
                }

                val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val requestBody = RequestBody.create(mediaType, payload.toString())

                val request = Request.Builder()
                    .url(stateUrl)
                    .addHeader("Authorization", "Bearer ${config.haToken}")
                    .post(requestBody)
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e("HaWebSocketClient", "Failed to update state in HA: $entityId", e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            if (!response.isSuccessful) {
                                Log.e("HaWebSocketClient", "HA state update returned error code ${response.code}: ${response.message}")
                            } else {
                                Log.d("HaWebSocketClient", "Successful state update in HA: $entityId -> $lastState")
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                Log.e("HaWebSocketClient", "Error executing state push to HA", e)
            }
        }
    }

    companion object {
        fun getHaUrls(inputAddress: String): Pair<String, String> {
            var clean = inputAddress.trim()
            if (clean.endsWith("/")) {
                clean = clean.dropLast(1)
            }

            val baseScheme: String
            val hostPort: String

            if (clean.startsWith("http://", ignoreCase = true)) {
                baseScheme = "http"
                hostPort = clean.substring(7)
            } else if (clean.startsWith("https://", ignoreCase = true)) {
                baseScheme = "https"
                hostPort = clean.substring(8)
            } else if (clean.startsWith("ws://", ignoreCase = true)) {
                baseScheme = "http"
                hostPort = clean.substring(5)
            } else if (clean.startsWith("wss://", ignoreCase = true)) {
                baseScheme = "https"
                hostPort = clean.substring(6)
            } else {
                baseScheme = "http" // default fallback
                hostPort = clean
            }

            // Strip trailing API endpoints if they were pasted by the user
            var hostPortClean = hostPort
            if (hostPortClean.endsWith("/api/websocket")) {
                hostPortClean = hostPortClean.substring(0, hostPortClean.length - 14)
            } else if (hostPortClean.endsWith("/api")) {
                hostPortClean = hostPortClean.substring(0, hostPortClean.length - 4)
            }
            if (hostPortClean.endsWith("/")) {
                hostPortClean = hostPortClean.dropLast(1)
            }

            val httpUrl = "$baseScheme://$hostPortClean"
            val wsScheme = if (baseScheme == "https") "wss" else "ws"
            val wsUrl = "$wsScheme://$hostPortClean/api/websocket"

            return wsUrl to httpUrl
        }
    }
}
