package com.example.api

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import com.example.data.model.AppConfig
import com.example.data.model.MediaHistory
import com.example.data.repository.AppRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*

object ReceiverManager {

    private var context: Context? = null
    private var repository: AppRepository? = null

    // Unique UUID for MQTT client ID and HA Discovery isolation, persisted per launch to avoid cluttering
    val deviceUuid: String by lazy {
        val prefs = context!!.getSharedPreferences("tv_media_player_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("device_uuid", null)
        if (id == null) {
            id = UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString("device_uuid", id).apply()
        }
        id
    }

    // Active media player status states
    private val _playerState = MutableStateFlow("idle") // "playing", "paused", "idle"
    val playerState: StateFlow<String> = _playerState.asStateFlow()

    private val _volume = MutableStateFlow(70) // 0 to 100
    val volume: StateFlow<Int> = _volume.asStateFlow()

    private val _mediaTitle = MutableStateFlow("Ambient Screen")
    val mediaTitle: StateFlow<String> = _mediaTitle.asStateFlow()

    private val _mediaSubtitle = MutableStateFlow("Waiting for Home Assistant")
    val mediaSubtitle: StateFlow<String> = _mediaSubtitle.asStateFlow()

    private val _activeMediaUrl = MutableStateFlow<String?>(null)
    val activeMediaUrl: StateFlow<String?> = _activeMediaUrl.asStateFlow()

    private val _mediaDuration = MutableStateFlow(0)
    val mediaDuration: StateFlow<Int> = _mediaDuration.asStateFlow()

    private val _mediaPosition = MutableStateFlow(0)
    val mediaPosition: StateFlow<Int> = _mediaPosition.asStateFlow()

    private val _mqttConnected = MutableStateFlow(false)
    val mqttConnected: StateFlow<Boolean> = _mqttConnected.asStateFlow()

    private val _mqttError = MutableStateFlow<String?>(null)
    val mqttError: StateFlow<String?> = _mqttError.asStateFlow()

    private val _haConnected = MutableStateFlow(false)
    val haConnected: StateFlow<Boolean> = _haConnected.asStateFlow()

    private val _haError = MutableStateFlow<String?>(null)
    val haError: StateFlow<String?> = _haError.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var textToSpeech: TextToSpeech? = null

    private var apiServer: ApiServer? = null
    private var mqttHandler: MqttServiceHandler? = null
    private var haClient: HaWebSocketClient? = null

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var sysAudioManager: AudioManager? = null

    private var activeConfig: AppConfig? = null

    private var progressTrackingJob: Job? = null
    private var configJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun init(appContext: Context, repo: AppRepository) {
        if (context != null) return // Already initialized
        context = appContext
        repository = repo

        sysAudioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        textToSpeech = TextToSpeech(appContext, object : TextToSpeech.OnInitListener {
            override fun onInit(status: Int) {
                if (status == TextToSpeech.SUCCESS) {
                    textToSpeech?.setLanguage(Locale("pt", "BR"))
                    Log.d("ReceiverManager", "TTS successfully initialized")
                } else {
                    Log.e("ReceiverManager", "TTS Initialization failed")
                }
            }
        })

        updateSystemVolumeState()

        // Sync and setup configuration observer
        configJob = scope.launch {
            repo.appConfig.collectLatest { config ->
                if (config == null) return@collectLatest
                val oldConfig = activeConfig
                activeConfig = config

                // Port & API Server
                if (oldConfig == null || oldConfig.serverPort != config.serverPort || oldConfig.deviceName != config.deviceName || apiServer == null) {
                    apiServer?.stop()
                    apiServer = ApiServer(config.serverPort, createApiListener())
                    apiServer?.start()
                    registerMdnsService(config.serverPort, config.deviceName)
                }

                // MQTT Integration
                if (oldConfig == null ||
                    oldConfig.mqttEnabled != config.mqttEnabled ||
                    oldConfig.mqttBroker != config.mqttBroker ||
                    oldConfig.mqttPort != config.mqttPort ||
                    oldConfig.mqttUser != config.mqttUser ||
                    oldConfig.mqttPassword != config.mqttPassword ||
                    oldConfig.deviceName != config.deviceName ||
                    mqttHandler == null) {

                    mqttHandler?.disconnect()
                    if (config.mqttEnabled && config.mqttBroker.isNotEmpty()) {
                        mqttHandler = MqttServiceHandler(config, deviceUuid, createMqttListener())
                        mqttHandler?.connect()
                    } else {
                        _mqttConnected.value = false
                        _mqttError.value = "MQTT settings are disabled or empty"
                    }
                }

                // Home Assistant WS
                if (oldConfig == null ||
                    oldConfig.haEnabled != config.haEnabled ||
                    oldConfig.haAddress != config.haAddress ||
                    oldConfig.haToken != config.haToken ||
                    oldConfig.deviceName != config.deviceName ||
                    haClient == null) {

                    haClient?.disconnect()
                    if (config.haEnabled && config.haAddress.isNotEmpty() && config.haToken.isNotEmpty()) {
                        haClient = HaWebSocketClient(config, deviceUuid, createHaListener())
                        haClient?.connect()
                    } else {
                        _haConnected.value = false
                        _haError.value = "HA settings are disabled or incomplete"
                    }
                }
            }
        }

        // Setup live bidirectional updates to caches
        scope.launch {
            _playerState.collectLatest { state ->
                haClient?.updateStateCache(state)
                mqttHandler?.publishState(state)
            }
        }

        scope.launch {
            _volume.collectLatest { valPct ->
                haClient?.updateVolumeCache(valPct)
                mqttHandler?.publishVolume(valPct)
            }
        }

        scope.launch {
            _mediaTitle.collectLatest { title ->
                val metaSub = _mediaSubtitle.value
                haClient?.updateMediaCache(title, metaSub)
                mqttHandler?.publishMediaInfo(title, metaSub)
            }
        }

        scope.launch {
            _mediaSubtitle.collectLatest { subtitle ->
                val metaTitle = _mediaTitle.value
                haClient?.updateMediaCache(metaTitle, subtitle)
                mqttHandler?.publishMediaInfo(metaTitle, subtitle)
            }
        }
    }

    // --- Media Player Engine Methods ---

    fun playDirectMediaStream(url: String, title: String, artist: String) {
        scope.launch {
            try {
                stopActiveMediaPlayer()

                mediaPlayer = MediaPlayer().apply {
                    setDataSource(url)
                    setAudioStreamType(AudioManager.STREAM_MUSIC)
                    setOnPreparedListener { mp ->
                        mp.start()
                        _playerState.value = "playing"
                        _mediaTitle.value = title
                        _mediaSubtitle.value = artist
                        _activeMediaUrl.value = url
                        
                        logPlayHistory(title, artist)

                        mqttHandler?.publishState("playing")
                        mqttHandler?.publishMediaInfo(title, artist)

                        startProgressTimer()
                    }
                    setOnCompletionListener {
                        stopActiveMediaPlayer()
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e("ReceiverManager", "MediaPlayer error: what=$what, extra=$extra")
                        stopActiveMediaPlayer()
                        true
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                Log.e("ReceiverManager", "Failed to load stream url", e)
                withContext(Dispatchers.Main) {
                    context?.let {
                        Toast.makeText(it, "Erro ao carregar mídia do HA", Toast.LENGTH_SHORT).show()
                    }
                }
                stopActiveMediaPlayer()
            }
        }
    }

    private fun startProgressTimer() {
        progressTrackingJob?.cancel()
        progressTrackingJob = scope.launch {
            while (true) {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        val posSec = mp.currentPosition / 1000
                        val durSec = mp.duration / 1000
                        _mediaPosition.value = posSec
                        _mediaDuration.value = durSec
                    }
                }
                delay(1000)
            }
        }
    }

    fun handlePlayPauseIntent() {
        mediaPlayer?.let { mp ->
            if (mp.isPlaying) {
                pauseActiveMediaPlayer()
            } else {
                resumeActiveMediaPlayer()
            }
        }
    }

    fun resumeActiveMediaPlayer() {
        mediaPlayer?.let { mp ->
            if (!mp.isPlaying) {
                mp.start()
                _playerState.value = "playing"
                mqttHandler?.publishState("playing")
                startProgressTimer()
            }
        }
    }

    fun pauseActiveMediaPlayer() {
        mediaPlayer?.let { mp ->
            if (mp.isPlaying) {
                mp.pause()
                _playerState.value = "paused"
                mqttHandler?.publishState("paused")
                progressTrackingJob?.cancel()
            }
        }
    }

    fun stopActiveMediaPlayer() {
        progressTrackingJob?.cancel()
        mediaPlayer?.let { mp ->
            try {
                if (mp.isPlaying) mp.stop()
                mp.release()
            } catch (e: Exception) {
                Log.e("ReceiverManager", "Error stopping player", e)
            }
        }
        mediaPlayer = null
        _playerState.value = "idle"
        _mediaTitle.value = "Ambient Screen"
        _mediaSubtitle.value = "Waiting for Home Assistant"
        _activeMediaUrl.value = null
        _mediaPosition.value = 0
        _mediaDuration.value = 0
        mqttHandler?.publishState("idle")
    }

    // --- Audio Control Methods ---

    fun updateSystemVolumeState() {
        sysAudioManager?.let { am ->
            val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val percent = if (maxVol > 0) (curVol * 100) / maxVol else 70
            _volume.value = percent
            mqttHandler?.publishVolume(percent)
        }
    }

    fun setSystemVolume(volPercent: Int) {
        sysAudioManager?.let { am ->
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val level = (volPercent.toFloat() / 100f * maxVol).toInt().coerceIn(0, maxVol)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
            _volume.value = volPercent
            mqttHandler?.publishVolume(volPercent)
        }
    }

    // --- Text to Speech Engine ---

    fun fireTextToSpeech(text: String) {
        val isCurrentlyPlaying = mediaPlayer?.isPlaying == true
        if (isCurrentlyPlaying) {
            mediaPlayer?.setVolume(0.15f, 0.15f)
        }

        logTtsHistory(text)

        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ha_speech_id")

        scope.launch {
            delay(5000)
            if (isCurrentlyPlaying) {
                mediaPlayer?.setVolume(1.0f, 1.0f)
            }
        }
    }

    // --- External TV Launcher Application ---

    fun launchExternalTVApp(packageName: String) {
        try {
            val intent = context?.packageManager?.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context?.startActivity(intent)
                Log.d("ReceiverManager", "Successfully launched $packageName")
            } else {
                Log.e("ReceiverManager", "App not found on TV: $packageName")
                scope.launch {
                    context?.let {
                        Toast.makeText(it, "Aplicativo não disponível nesta TV: $packageName", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ReceiverManager", "Launch application error", e)
        }
    }

    // --- Database Logging ---

    private fun logPlayHistory(title: String, subtitle: String) {
        scope.launch {
            repository?.logMediaHistory(
                MediaHistory(
                    type = "MEDIA",
                    title = title,
                    subtitle = subtitle,
                    duration = if (_mediaDuration.value > 0) formatTime(_mediaDuration.value) else "Live"
                )
            )
        }
    }

    private fun logTtsHistory(text: String) {
        scope.launch {
            repository?.logMediaHistory(
                MediaHistory(
                    type = "TTS",
                    title = "Announced Text",
                    subtitle = text,
                    duration = "Speech"
                )
            )
        }
    }

    private fun formatTime(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format("%02d:%02d:%02d", h, m, s)
        } else {
            String.format("%02d:%02d", m, s)
        }
    }

    // --- Service Lifecycle Release Actions ---

    fun destroy() {
        configJob?.cancel()
        unregisterMdnsService()
        apiServer?.stop()
        mqttHandler?.disconnect()
        haClient?.disconnect()
        mediaPlayer?.release()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        progressTrackingJob?.cancel()

        apiServer = null
        mqttHandler = null
        haClient = null
        mediaPlayer = null
        textToSpeech = null
        context = null
        repository = null
    }

    // --- API and Connection Listeners ---

    private fun createApiListener() = object : ApiServer.ApiServerListener {
        override fun onPlay() = resumeActiveMediaPlayer()
        override fun onPause() = pauseActiveMediaPlayer()
        override fun onStop() = stopActiveMediaPlayer()
        override fun onVolumeSet(volume: Int) = setSystemVolume(volume)
        override fun onPlayMedia(url: String, title: String?, subtitle: String?) = playDirectMediaStream(url, title ?: "Streaming Media", subtitle ?: "Remote Stream")
        override fun onTextToSpeech(text: String) = fireTextToSpeech(text)
        override fun onOpenApp(packageName: String) = launchExternalTVApp(packageName)

        override fun getPlayerStatus(): ApiServer.PlayerStatus {
            return ApiServer.PlayerStatus(
                state = _playerState.value,
                volume = _volume.value,
                mediaTitle = _mediaTitle.value,
                mediaSubtitle = _mediaSubtitle.value,
                mediaDuration = _mediaDuration.value,
                mediaPosition = _mediaPosition.value,
                deviceName = activeConfig?.deviceName ?: "Smart Receiver TV"
            )
        }
    }

    private fun createMqttListener() = object : MqttServiceHandler.MqttListener {
        override fun onMqttPlay() = resumeActiveMediaPlayer()
        override fun onMqttPause() = pauseActiveMediaPlayer()
        override fun onMqttStop() = stopActiveMediaPlayer()
        override fun onMqttVolumeSet(volume: Int) = setSystemVolume(volume)
        override fun onMqttPlayUrl(url: String) = playDirectMediaStream(url, "MQTT Stream", "Home Assistant Action")
        override fun onMqttSay(text: String) = fireTextToSpeech(text)

        override fun onMqttStatusChanged(connected: Boolean, errorMsg: String?) {
            _mqttConnected.value = connected
            _mqttError.value = errorMsg
        }
    }

    private fun createHaListener() = object : HaWebSocketClient.HaWebSocketListener {
        override fun onHaPlay() = resumeActiveMediaPlayer()
        override fun onHaPause() = pauseActiveMediaPlayer()
        override fun onHaStop() = stopActiveMediaPlayer()
        override fun onHaPlayPause() = handlePlayPauseIntent()
        override fun onHaVolumeSet(volume: Int) = setSystemVolume(volume)
        override fun onHaPlayMedia(url: String, title: String?, subtitle: String?) = playDirectMediaStream(url, title ?: "Streaming Media", subtitle ?: "Remote Stream")
        override fun onHaTextToSpeech(text: String) = fireTextToSpeech(text)
        override fun onHaOpenApp(packageName: String) = launchExternalTVApp(packageName)

        override fun onHaStatusChanged(connected: Boolean, errorMsg: String?) {
            _haConnected.value = connected
            _haError.value = errorMsg
        }
    }

    // --- Discovery Methods ---

    private fun registerMdnsService(port: Int, deviceName: String) {
        unregisterMdnsService()

        try {
            context?.let { ctx ->
                nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
                val serviceInfo = NsdServiceInfo().apply {
                    this.serviceName = deviceName
                    this.serviceType = "_http._tcp."
                    this.port = port
                }

                registrationListener = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(regServiceInfo: NsdServiceInfo) {
                        Log.d("ReceiverManager", "mDNS service registered successfully: ${regServiceInfo.serviceName}")
                    }

                    override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e("ReceiverManager", "mDNS service registration failed: $errorCode")
                    }

                    override fun onServiceUnregistered(regServiceInfo: NsdServiceInfo) {
                        Log.d("ReceiverManager", "mDNS service unregistered: ${regServiceInfo.serviceName}")
                    }

                    override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e("ReceiverManager", "mDNS service unregistration failed: $errorCode")
                    }
                }

                nsdManager?.registerService(
                    serviceInfo,
                    NsdManager.PROTOCOL_DNS_SD,
                    registrationListener
                )
            }
        } catch (e: Exception) {
            Log.e("ReceiverManager", "Error registering mDNS service", e)
        }
    }

    private fun unregisterMdnsService() {
        try {
            if (nsdManager != null && registrationListener != null) {
                nsdManager?.unregisterService(registrationListener)
            }
        } catch (e: Exception) {
            Log.e("ReceiverManager", "Error unregistering mDNS service", e)
        } finally {
            nsdManager = null
            registrationListener = null
        }
    }
}
