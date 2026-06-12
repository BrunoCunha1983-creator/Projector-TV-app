package com.example

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.api.ApiServer
import com.example.api.MqttServiceHandler
import com.example.api.HaWebSocketClient
import com.example.data.db.AppDatabase
import com.example.data.model.AppConfig
import com.example.data.repository.AppRepository
import com.example.ui.screens.TvDashboardScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var viewModel: MainViewModel
    private var mediaPlayer: MediaPlayer? = null
    private var textToSpeech: TextToSpeech? = null

    private var apiServer: ApiServer? = null
    private var mqttHandler: MqttServiceHandler? = null
    private var haClient: HaWebSocketClient? = null

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    private var activeConfig: AppConfig? = null

    // Listeners for MediaPlayer progress tracking
    private var progressTrackingJob: Job? = null
    private var sysAudioManager: AudioManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Setup Audio & TTS Services
        sysAudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        textToSpeech = TextToSpeech(this, this)

        // 2. Setup ViewModel and Repositories
        val database = AppDatabase.getDatabase(this)
        val repository = AppRepository(database.appDao())
        viewModel = MainViewModel(application, repository)

        // Sync initial device volume state
        updateSystemVolumeState()

        // 3. Render Screen
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TvDashboardScreen(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize(),
                        onManualPlayPause = { handlePlayPauseIntent() },
                        onManualStop = { stopActiveMediaPlayer() }
                    )
                }
            }
        }

        // 4. Start background service and state observers
        setupStateObservers()
    }

    private fun setupStateObservers() {
        // Observe configurations dynamically to start/restart server or MQTT client
        lifecycleScope.launch {
            viewModel.appConfigFlow.collectLatest { config ->
                if (config == null) return@collectLatest
                val oldConfig = activeConfig
                activeConfig = config

                // Check If Port server or device name needs restarting/registering mDNS
                if (oldConfig == null || oldConfig.serverPort != config.serverPort || oldConfig.deviceName != config.deviceName || apiServer == null) {
                    apiServer?.stop()
                    apiServer = ApiServer(config.serverPort, createApiListener())
                    apiServer?.start()
                    registerMdnsService(config.serverPort, config.deviceName)
                }

                // Check If MQTT configuration changed
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
                        mqttHandler = MqttServiceHandler(config, viewModel.deviceUuid, createMqttListener())
                        mqttHandler?.connect()
                    } else {
                        viewModel.updateMqttStatus(false, "MQTT custom settings are disabled or empty")
                    }
                }

                // Check If Home Assistant WebSocket configuration changed
                if (oldConfig == null ||
                    oldConfig.haEnabled != config.haEnabled ||
                    oldConfig.haAddress != config.haAddress ||
                    oldConfig.haToken != config.haToken ||
                    oldConfig.deviceName != config.deviceName ||
                    haClient == null) {
                    
                    haClient?.disconnect()
                    if (config.haEnabled && config.haAddress.isNotEmpty() && config.haToken.isNotEmpty()) {
                        haClient = HaWebSocketClient(config, viewModel.deviceUuid, createHaListener())
                        haClient?.connect()
                    } else {
                        viewModel.updateHaStatus(false, "HA settings are disabled or incomplete")
                    }
                }
            }
        }

        // Live bidirectional state synchronizer to HA and MQTT
        lifecycleScope.launch {
            viewModel.playerState.collectLatest { state ->
                haClient?.updateStateCache(state)
                mqttHandler?.publishState(state)
            }
        }

        lifecycleScope.launch {
            viewModel.volume.collectLatest { volume ->
                haClient?.updateVolumeCache(volume)
                mqttHandler?.publishVolume(volume)
            }
        }

        lifecycleScope.launch {
            viewModel.mediaTitle.collectLatest { title ->
                haClient?.updateMediaCache(title, viewModel.mediaSubtitle.value)
                mqttHandler?.publishMediaInfo(title, viewModel.mediaSubtitle.value)
            }
        }

        lifecycleScope.launch {
            viewModel.mediaSubtitle.collectLatest { subtitle ->
                haClient?.updateMediaCache(viewModel.mediaTitle.value, subtitle)
                mqttHandler?.publishMediaInfo(viewModel.mediaTitle.value, subtitle)
            }
        }

        // Observe media remote commands
        lifecycleScope.launch {
            viewModel.actionPlay.collectLatest { timestamp ->
                if (timestamp > 0) resumeActiveMediaPlayer()
            }
        }

        lifecycleScope.launch {
            viewModel.actionPause.collectLatest { timestamp ->
                if (timestamp > 0) pauseActiveMediaPlayer()
            }
        }

        lifecycleScope.launch {
            viewModel.actionStop.collectLatest { timestamp ->
                if (timestamp > 0) stopActiveMediaPlayer()
            }
        }

        lifecycleScope.launch {
            viewModel.actionSetVolume.collectLatest { pair ->
                val (timestamp, volPercent) = pair
                if (timestamp > 0) {
                    setSystemVolume(volPercent)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.actionPlayUrl.collectLatest { triple ->
                val (timestamp, url, meta) = triple
                if (timestamp > 0 && url.isNotEmpty()) {
                    val split = meta.split("|")
                    val title = split.getOrNull(0) ?: "Stream Feed"
                    val artist = split.getOrNull(1) ?: "Home Assistant"
                    playDirectMediaStream(url, title, artist)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.actionTts.collectLatest { pair ->
                val (timestamp, text) = pair
                if (timestamp > 0 && text.isNotEmpty()) {
                    fireTextToSpeech(text)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.actionLaunchPkg.collectLatest { pair ->
                val (timestamp, pkgName) = pair
                if (timestamp > 0 && pkgName.isNotEmpty()) {
                    launchExternalTVApp(pkgName)
                }
            }
        }
    }

    // --- Media Player Engine Methods ---

    private fun playDirectMediaStream(url: String, title: String, artist: String) {
        lifecycleScope.launch {
            try {
                // Reset existing player
                stopActiveMediaPlayer()

                mediaPlayer = MediaPlayer().apply {
                    setDataSource(url)
                    setAudioStreamType(AudioManager.STREAM_MUSIC)
                    setOnPreparedListener { mp ->
                        mp.start()
                        viewModel.updatePlayerState("playing")
                        viewModel.updateMediaInfo(title, artist, url)
                        viewModel.logPlayHistory(title, artist)
                        
                        // Sync status back to MQTT
                        mqttHandler?.publishState("playing")
                        mqttHandler?.publishMediaInfo(title, artist)

                        // Start tracking duration progress
                        startProgressTimer()
                    }
                    setOnCompletionListener {
                        stopActiveMediaPlayer()
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e("MainActivity", "MediaPlayer error: what=$what, extra=$extra")
                        stopActiveMediaPlayer()
                        true
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load stream url", e)
                Toast.makeText(this@MainActivity, "Erro ao carregar mídia do HA", Toast.LENGTH_SHORT).show()
                stopActiveMediaPlayer()
            }
        }
    }

    private fun startProgressTimer() {
        progressTrackingJob?.cancel()
        progressTrackingJob = lifecycleScope.launch {
            while (true) {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        val posSec = mp.currentPosition / 1000
                        val durSec = mp.duration / 1000
                        viewModel.updateVideoProgress(posSec, durSec)
                    }
                }
                delay(1000)
            }
        }
    }

    private fun handlePlayPauseIntent() {
        mediaPlayer?.let { mp ->
            if (mp.isPlaying) {
                pauseActiveMediaPlayer()
            } else {
                resumeActiveMediaPlayer()
            }
        }
    }

    private fun resumeActiveMediaPlayer() {
        mediaPlayer?.let { mp ->
            if (!mp.isPlaying) {
                mp.start()
                viewModel.updatePlayerState("playing")
                mqttHandler?.publishState("playing")
                startProgressTimer()
            }
        }
    }

    private fun pauseActiveMediaPlayer() {
        mediaPlayer?.let { mp ->
            if (mp.isPlaying) {
                mp.pause()
                viewModel.updatePlayerState("paused")
                mqttHandler?.publishState("paused")
                progressTrackingJob?.cancel()
            }
        }
    }

    private fun stopActiveMediaPlayer() {
        progressTrackingJob?.cancel()
        mediaPlayer?.let { mp ->
            try {
                if (mp.isPlaying) mp.stop()
                mp.release()
            } catch (e: Exception) {
                Log.e("MainActivity", "Error stopping player", e)
            }
        }
        mediaPlayer = null
        viewModel.updatePlayerState("idle")
        viewModel.updateMediaInfo("Ambient Screen", "Waiting for Home Assistant", null)
        viewModel.updateVideoProgress(0, 0)
        mqttHandler?.publishState("idle")
    }

    // --- Audio Control Methods ---

    private fun updateSystemVolumeState() {
        sysAudioManager?.let { am ->
            val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val percent = if (maxVol > 0) (curVol * 100) / maxVol else 70
            viewModel.updateVolume(percent)
            mqttHandler?.publishVolume(percent)
        }
    }

    private fun setSystemVolume(volPercent: Int) {
        sysAudioManager?.let { am ->
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val level = (volPercent.toFloat() / 100f * maxVol).toInt().coerceIn(0, maxVol)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
            viewModel.updateVolume(volPercent)
            mqttHandler?.publishVolume(volPercent)
        }
    }

    // --- Text to Speech Engine ---

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale("pt", "BR"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                textToSpeech?.language = Locale.getDefault()
            }
            Log.d("MainActivity", "TTS successfully initialized")
        } else {
            Log.e("MainActivity", "TTS Initialization failed")
        }
    }

    private fun fireTextToSpeech(text: String) {
        // Duck the music if we are currently playing media
        val isCurrentlyPlaying = mediaPlayer?.isPlaying == true
        if (isCurrentlyPlaying) {
            mediaPlayer?.setVolume(0.15f, 0.15f)
        }

        viewModel.logTtsHistory(text)

        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ha_speech_id")

        // Wait for TTS to speak and then restore volume
        lifecycleScope.launch {
            delay(5000) // standard notification window length fallback
            if (isCurrentlyPlaying) {
                mediaPlayer?.setVolume(1.0f, 1.0f)
            }
        }
    }

    // --- External TV Launcher Application ---

    private fun launchExternalTVApp(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Log.d("MainActivity", "Successfully launched $packageName")
            } else {
                Log.e("MainActivity", "App not found on TV: $packageName")
                Toast.makeText(this, "Aplicativo não disponível nesta TV: $packageName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Launch application error", e)
        }
    }

    // --- Network Api Server Hook Listeners ---

    private fun createApiListener() = object : ApiServer.ApiServerListener {
        override fun onPlay() = viewModel.triggerPlayByRemote()
        override fun onPause() = viewModel.triggerPauseByRemote()
        override fun onStop() = viewModel.triggerStopByRemote()
        override fun onVolumeSet(volume: Int) = viewModel.triggerVolumeByRemote(volume)
        override fun onPlayMedia(url: String, title: String?, subtitle: String?) = viewModel.triggerPlayMediaByRemote(url, title, subtitle)
        override fun onTextToSpeech(text: String) = viewModel.triggerTtsByRemote(text)
        override fun onOpenApp(packageName: String) = viewModel.triggerLaunchPkgByRemote(packageName)

        override fun getPlayerStatus(): ApiServer.PlayerStatus {
            return ApiServer.PlayerStatus(
                state = viewModel.playerState.value,
                volume = viewModel.volume.value,
                mediaTitle = viewModel.mediaTitle.value,
                mediaSubtitle = viewModel.mediaSubtitle.value,
                mediaDuration = viewModel.mediaDuration.value,
                mediaPosition = viewModel.mediaPosition.value,
                deviceName = activeConfig?.deviceName ?: "Smart Receiver TV"
            )
        }
    }

    // --- MQTT Connect Listener Hooks ---

    private fun createMqttListener() = object : MqttServiceHandler.MqttListener {
        override fun onMqttPlay() = viewModel.triggerPlayByRemote()
        override fun onMqttPause() = viewModel.triggerPauseByRemote()
        override fun onMqttStop() = viewModel.triggerStopByRemote()
        override fun onMqttVolumeSet(volume: Int) = viewModel.triggerVolumeByRemote(volume)
        override fun onMqttPlayUrl(url: String) = viewModel.triggerPlayMediaByRemote(url, "MQTT Stream", "Home Assistant Action")
        override fun onMqttSay(text: String) = viewModel.triggerTtsByRemote(text)

        override fun onMqttStatusChanged(connected: Boolean, errorMsg: String?) {
            viewModel.updateMqttStatus(connected, errorMsg)
        }
    }

    // --- Home Assistant Connect Listener Hooks ---

    private fun createHaListener() = object : HaWebSocketClient.HaWebSocketListener {
        override fun onHaPlay() = viewModel.triggerPlayByRemote()
        override fun onHaPause() = viewModel.triggerPauseByRemote()
        override fun onHaStop() = viewModel.triggerStopByRemote()
        override fun onHaPlayPause() = handlePlayPauseIntent()
        override fun onHaVolumeSet(volume: Int) = viewModel.triggerVolumeByRemote(volume)
        override fun onHaPlayMedia(url: String, title: String?, subtitle: String?) = viewModel.triggerPlayMediaByRemote(url, title, subtitle)
        override fun onHaTextToSpeech(text: String) = viewModel.triggerTtsByRemote(text)
        override fun onHaOpenApp(packageName: String) = viewModel.triggerLaunchPkgByRemote(packageName)

        override fun onHaStatusChanged(connected: Boolean, errorMsg: String?) {
            viewModel.updateHaStatus(connected, errorMsg)
        }
    }

    // --- Activity Lifecycle Hooks ---

    override fun onDestroy() {
        unregisterMdnsService()
        apiServer?.stop()
        mqttHandler?.disconnect()
        haClient?.disconnect()
        mediaPlayer?.release()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        super.onDestroy()
    }

    private fun registerMdnsService(port: Int, deviceName: String) {
        unregisterMdnsService()

        try {
            nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
            val serviceInfo = NsdServiceInfo().apply {
                this.serviceName = deviceName
                this.serviceType = "_http._tcp."
                this.port = port
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(regServiceInfo: NsdServiceInfo) {
                    Log.d("MainActivity", "mDNS service registered successfully: ${regServiceInfo.serviceName}")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e("MainActivity", "mDNS service registration failed: $errorCode")
                }

                override fun onServiceUnregistered(regServiceInfo: NsdServiceInfo) {
                    Log.d("MainActivity", "mDNS service unregistered: ${regServiceInfo.serviceName}")
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e("MainActivity", "mDNS service unregistration failed: $errorCode")
                }
            }

            nsdManager?.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                registrationListener
            )
        } catch (e: Exception) {
            Log.e("MainActivity", "Error registering mDNS service", e)
        }
    }

    private fun unregisterMdnsService() {
        try {
            if (nsdManager != null && registrationListener != null) {
                nsdManager?.unregisterService(registrationListener)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregistering mDNS service", e)
        } finally {
            nsdManager = null
            registrationListener = null
        }
    }
}
