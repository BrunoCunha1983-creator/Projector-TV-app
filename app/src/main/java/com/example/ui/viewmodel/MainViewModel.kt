package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.api.ApiServer
import com.example.data.db.AppDatabase
import com.example.data.model.AppConfig
import com.example.data.model.MediaHistory
import com.example.data.repository.AppRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class MainViewModel(
    application: Application,
    private val repository: AppRepository
) : AndroidViewModel(application) {

    // Unique UUID for MQTT client ID and HA Discovery isolation, persisted per launch to avoid cluttering
    val deviceUuid: String = run {
        val prefs = application.getSharedPreferences("tv_media_player_prefs", android.content.Context.MODE_PRIVATE)
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

    private val _localIp = MutableStateFlow(ApiServer.getLocalIpAddress())
    val localIp: StateFlow<String> = _localIp.asStateFlow()

    private val _mqttConnected = MutableStateFlow(false)
    val mqttConnected: StateFlow<Boolean> = _mqttConnected.asStateFlow()

    private val _mqttError = MutableStateFlow<String?>(null)
    val mqttError: StateFlow<String?> = _mqttError.asStateFlow()

    private val _haConnected = MutableStateFlow(false)
    val haConnected: StateFlow<Boolean> = _haConnected.asStateFlow()

    private val _haError = MutableStateFlow<String?>(null)
    val haError: StateFlow<String?> = _haError.asStateFlow()

    // Command channels to communicate back to MainActivity (observed from MainActivity)
    private val _actionPlay = MutableStateFlow<Long>(0)
    val actionPlay: StateFlow<Long> = _actionPlay.asStateFlow()

    private val _actionPause = MutableStateFlow<Long>(0)
    val actionPause: StateFlow<Long> = _actionPause.asStateFlow()

    private val _actionStop = MutableStateFlow<Long>(0)
    val actionStop: StateFlow<Long> = _actionStop.asStateFlow()

    private val _actionSetVolume = MutableStateFlow<Pair<Long, Int>>(0L to 70)
    val actionSetVolume: StateFlow<Pair<Long, Int>> = _actionSetVolume.asStateFlow()

    private val _actionPlayUrl = MutableStateFlow<Triple<Long, String, String>>(Triple(0L, "", ""))
    val actionPlayUrl: StateFlow<Triple<Long, String, String>> = _actionPlayUrl.asStateFlow()

    private val _actionTts = MutableStateFlow<Pair<Long, String>>(0L to "")
    val actionTts: StateFlow<Pair<Long, String>> = _actionTts.asStateFlow()

    private val _actionLaunchPkg = MutableStateFlow<Pair<Long, String>>(0L to "")
    val actionLaunchPkg: StateFlow<Pair<Long, String>> = _actionLaunchPkg.asStateFlow()

    // Room configurations
    val appConfigFlow = repository.appConfig
    val mediaHistoryFlow = repository.mediaHistory

    init {
        // Build initial config row if it doesn't exist
        viewModelScope.launch {
            val current = repository.getAppConfigSuspend()
            if (current == null) {
                repository.saveAppConfig(AppConfig(id = 0))
            }
        }
    }

    fun refreshIp() {
        _localIp.value = ApiServer.getLocalIpAddress()
    }

    // Setters called from UI to update db
    fun saveConfig(config: AppConfig) {
        viewModelScope.launch {
            repository.saveAppConfig(config)
        }
    }

    // Callback handlers called by API Server & MQTT Service handlers
    fun triggerPlayByRemote() {
        _actionPlay.value = System.currentTimeMillis()
    }

    fun triggerPauseByRemote() {
        _actionPause.value = System.currentTimeMillis()
    }

    fun triggerStopByRemote() {
        _actionStop.value = System.currentTimeMillis()
    }

    fun triggerVolumeByRemote(vol: Int) {
        _actionSetVolume.value = System.currentTimeMillis() to vol
    }

    fun triggerPlayMediaByRemote(url: String, title: String?, subtitle: String?) {
        val finalTitle = title ?: "Streaming Media"
        val finalSubtitle = subtitle ?: "Remote Stream"
        _actionPlayUrl.value = Triple(System.currentTimeMillis(), url, "$finalTitle|$finalSubtitle")
    }

    fun triggerTtsByRemote(text: String) {
        _actionTts.value = System.currentTimeMillis() to text
    }

    fun triggerLaunchPkgByRemote(pkg: String) {
        _actionLaunchPkg.value = System.currentTimeMillis() to pkg
    }

    // Update state called by actual visual player inside MainActivity once action starts
    fun updatePlayerState(state: String) {
        _playerState.value = state
    }

    fun updateVolume(level: Int) {
        _volume.value = level
    }

    fun updateMediaInfo(title: String, subtitle: String, url: String? = null) {
        _mediaTitle.value = title
        _mediaSubtitle.value = subtitle
        _activeMediaUrl.value = url
    }

    fun updateVideoProgress(positionSec: Int, durationSec: Int) {
        _mediaPosition.value = positionSec
        _mediaDuration.value = durationSec
    }

    fun updateMqttStatus(connected: Boolean, errorMsg: String? = null) {
        _mqttConnected.value = connected
        _mqttError.value = errorMsg
    }

    fun updateHaStatus(connected: Boolean, errorMsg: String? = null) {
        _haConnected.value = connected
        _haError.value = errorMsg
    }

    // Log tracking inside SQLite DB
    fun logPlayHistory(title: String, subtitle: String) {
        viewModelScope.launch {
            repository.logMediaHistory(
                MediaHistory(
                    type = "MEDIA",
                    title = title,
                    subtitle = subtitle,
                    duration = if (_mediaDuration.value > 0) formatTime(_mediaDuration.value) else "Live"
                )
            )
        }
    }

    fun logTtsHistory(text: String) {
        viewModelScope.launch {
            repository.logMediaHistory(
                MediaHistory(
                    type = "TTS",
                    title = "Announced Text",
                    subtitle = text,
                    duration = "Speech"
                )
            )
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            repository.clearHistory()
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

    // Factory to instantiate the ViewModel safely
    class Factory(
        private val application: Application,
        private val repository: AppRepository
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return MainViewModel(application, repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
