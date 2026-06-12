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

    // Active media player status states delegated directly to background ReceiverManager
    val playerState: StateFlow<String> = com.example.api.ReceiverManager.playerState
    val volume: StateFlow<Int> = com.example.api.ReceiverManager.volume
    val mediaTitle: StateFlow<String> = com.example.api.ReceiverManager.mediaTitle
    val mediaSubtitle: StateFlow<String> = com.example.api.ReceiverManager.mediaSubtitle
    val activeMediaUrl: StateFlow<String?> = com.example.api.ReceiverManager.activeMediaUrl
    val mediaDuration: StateFlow<Int> = com.example.api.ReceiverManager.mediaDuration
    val mediaPosition: StateFlow<Int> = com.example.api.ReceiverManager.mediaPosition

    private val _localIp = MutableStateFlow(com.example.api.ApiServer.getLocalIpAddress())
    val localIp: StateFlow<String> = _localIp.asStateFlow()

    val mqttConnected: StateFlow<Boolean> = com.example.api.ReceiverManager.mqttConnected
    val mqttError: StateFlow<String?> = com.example.api.ReceiverManager.mqttError
    val haConnected: StateFlow<Boolean> = com.example.api.ReceiverManager.haConnected
    val haError: StateFlow<String?> = com.example.api.ReceiverManager.haError

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
        _localIp.value = com.example.api.ApiServer.getLocalIpAddress()
    }

    // Setters called from UI to update db
    fun saveConfig(config: AppConfig) {
        viewModelScope.launch {
            repository.saveAppConfig(config)
        }
    }

    // Remote triggers routed to the background ReceiverManager singleton
    fun triggerPlayByRemote() {
        com.example.api.ReceiverManager.resumeActiveMediaPlayer()
    }

    fun triggerPauseByRemote() {
        com.example.api.ReceiverManager.pauseActiveMediaPlayer()
    }

    fun triggerStopByRemote() {
        com.example.api.ReceiverManager.stopActiveMediaPlayer()
    }

    fun triggerVolumeByRemote(vol: Int) {
        com.example.api.ReceiverManager.setSystemVolume(vol)
    }

    fun triggerPlayMediaByRemote(url: String, title: String?, subtitle: String?) {
        com.example.api.ReceiverManager.playDirectMediaStream(url, title ?: "Streaming Media", subtitle ?: "Remote Stream")
    }

    fun triggerTtsByRemote(text: String) {
        com.example.api.ReceiverManager.fireTextToSpeech(text)
    }

    fun triggerLaunchPkgByRemote(pkg: String) {
        com.example.api.ReceiverManager.launchExternalTVApp(pkg)
    }

    fun updatePlayerState(state: String) {
        // Managed internally in ReceiverManager
    }

    fun updateVolume(level: Int) {
        com.example.api.ReceiverManager.setSystemVolume(level)
    }

    fun updateMediaInfo(title: String, subtitle: String, url: String? = null) {
        // Handled internally in ReceiverManager
    }

    fun updateVideoProgress(positionSec: Int, durationSec: Int) {
        // Handled internally in ReceiverManager
    }

    fun updateMqttStatus(connected: Boolean, errorMsg: String? = null) {
        // Handled internally in ReceiverManager
    }

    fun updateHaStatus(connected: Boolean, errorMsg: String? = null) {
        // Handled internally in ReceiverManager
    }

    // Log tracking inside SQLite DB
    fun logPlayHistory(title: String, subtitle: String) {
        viewModelScope.launch {
            repository.logMediaHistory(
                MediaHistory(
                    type = "MEDIA",
                    title = title,
                    subtitle = subtitle,
                    duration = if (mediaDuration.value > 0) formatTime(mediaDuration.value) else "Live"
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
