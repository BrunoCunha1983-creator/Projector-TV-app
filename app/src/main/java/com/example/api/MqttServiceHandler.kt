package com.example.api

import android.util.Log
import com.example.data.model.AppConfig
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.Executors

class MqttServiceHandler(
    private val config: AppConfig,
    private val uuid: String,
    private val listener: MqttListener
) {
    private var client: MqttClient? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var isConnectedInternal = false

    interface MqttListener {
        fun onMqttPlay()
        fun onMqttPause()
        fun onMqttStop()
        fun onMqttVolumeSet(volume: Int)
        fun onMqttPlayUrl(url: String)
        fun onMqttSay(text: String)
        fun onMqttStatusChanged(connected: Boolean, errorMsg: String? = null)
    }

    // Topics based on UUID for isolation
    private val baseTopic = "homeassistant/media_player/$uuid"
    private val stateTopic = "$baseTopic/state"
    private val availabilityTopic = "$baseTopic/availability"
    private val cmdTopic = "$baseTopic/cmd"
    private val volCmdTopic = "$baseTopic/vol_cmd"
    private val volStateTopic = "$baseTopic/vol_state"
    private val mediaTitleTopic = "$baseTopic/title"
    private val mediaArtistTopic = "$baseTopic/artist"
    private val playUrlCmdTopic = "$baseTopic/play_url_cmd"
    private val ttsCmdTopic = "$baseTopic/tts_cmd"

    fun connect() {
        if (!config.mqttEnabled || config.mqttBroker.isEmpty()) {
            listener.onMqttStatusChanged(false, "MQTT not enabled or Broker address empty")
            return
        }

        executor.execute {
            try {
                val brokerUrl = "tcp://${config.mqttBroker}:${config.mqttPort}"
                val clientId = "TvMediaPlayer_$uuid"
                
                Log.d("MqttServiceHandler", "Connecting to MQTT Broker: $brokerUrl with ID $clientId")
                
                client = MqttClient(brokerUrl, clientId, MemoryPersistence()).apply {
                    setCallback(object : MqttCallback {
                        override fun connectionLost(cause: Throwable?) {
                            Log.e("MqttServiceHandler", "Connection lost", cause)
                            isConnectedInternal = false
                            listener.onMqttStatusChanged(false, cause?.localizedMessage ?: "Connection lost")
                        }

                        override fun messageArrived(topic: String?, message: MqttMessage?) {
                            val payload = message?.payload?.let { String(it) } ?: return
                            Log.d("MqttServiceHandler", "Message arrived on $topic: $payload")
                            try {
                                when (topic) {
                                    cmdTopic -> {
                                        when (payload.uppercase()) {
                                            "PLAY" -> listener.onMqttPlay()
                                            "PAUSE" -> listener.onMqttPause()
                                            "STOP" -> listener.onMqttStop()
                                            "PLAY_PAUSE" -> {
                                                // Dynamic play/pause handled upstream
                                                listener.onMqttPlay()
                                            }
                                        }
                                    }
                                    volCmdTopic -> {
                                        payload.toIntOrNull()?.let { volume ->
                                            listener.onMqttVolumeSet(volume)
                                        }
                                    }
                                    playUrlCmdTopic -> {
                                        listener.onMqttPlayUrl(payload)
                                    }
                                    ttsCmdTopic -> {
                                        listener.onMqttSay(payload)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("MqttServiceHandler", "Error processing message", e)
                            }
                        }

                        override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                    })
                }

                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 30
                    if (config.mqttUser.isNotEmpty()) {
                        userName = config.mqttUser
                        password = config.mqttPassword.toCharArray()
                    }
                    // Configure LWT (Last Will & Testament)
                    setWill(availabilityTopic, "offline".toByteArray(), 1, true)
                }

                client?.connect(options)
                isConnectedInternal = true
                Log.d("MqttServiceHandler", "MQTT Connected successfully")

                // Step 1: Register Auto Discovery to Home Assistant
                publishAutoDiscovery()

                // Step 2: Subscribe to commands
                client?.subscribe(cmdTopic, 1)
                client?.subscribe(volCmdTopic, 1)
                client?.subscribe(playUrlCmdTopic, 1)
                client?.subscribe(ttsCmdTopic, 1)

                // Step 3: Inform availability and default status
                publish(availabilityTopic, "online", true)
                listener.onMqttStatusChanged(true)
                
            } catch (e: Exception) {
                Log.e("MqttServiceHandler", "MQTT connection error", e)
                isConnectedInternal = false
                listener.onMqttStatusChanged(false, e.localizedMessage ?: "Unknown error")
            }
        }
    }

    fun disconnect() {
        executor.execute {
            try {
                if (isConnectedInternal && client?.isConnected == true) {
                    publish(availabilityTopic, "offline", true)
                    client?.disconnect()
                }
                client = null
                isConnectedInternal = false
                Log.d("MqttServiceHandler", "MQTT Disconnected")
            } catch (e: Exception) {
                Log.e("MqttServiceHandler", "Error during MQTT disconnect", e)
            }
        }
    }

    fun isConnected() = isConnectedInternal && (client?.isConnected == true)

    private fun publishAutoDiscovery() {
        val topic = "homeassistant/media_player/${uuid}/config"
        val payload = """
            {
              "name": "${config.deviceName}",
              "unique_id": "tv_media_player_${uuid}",
              "state_topic": "$stateTopic",
              "command_topic": "$cmdTopic",
              "volume_command_topic": "$volCmdTopic",
              "volume_state_topic": "$volStateTopic",
              "availability_topic": "$availabilityTopic",
              "media_title_state_topic": "$mediaTitleTopic",
              "media_artist_state_topic": "$mediaArtistTopic",
              "device": {
                "identifiers": ["tv_media_player_${uuid}"],
                "name": "${config.deviceName}",
                "model": "Legacy Smart Receiver",
                "manufacturer": "Android TV Media Center",
                "sw_version": "1.0"
              }
            }
        """.trimIndent()
        
        publish(topic, payload, true)
        Log.d("MqttServiceHandler", "Published Auto Discovery payload to $topic")
    }

    fun publishState(state: String) {
        // HA states: 'playing', 'paused', 'idle', 'off'
        publish(stateTopic, state, true)
    }

    fun publishVolume(volume: Int) {
        publish(volStateTopic, volume.toString(), true)
    }

    fun publishMediaInfo(title: String, artist: String) {
        publish(mediaTitleTopic, title, true)
        publish(mediaArtistTopic, artist, true)
    }

    private fun publish(topic: String, payload: String, rsa: Boolean = false) {
        if (!isConnectedInternal) return
        executor.execute {
            try {
                val message = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
                    qos = 1
                    isRetained = rsa
                }
                client?.publish(topic, message)
            } catch (e: Exception) {
                Log.e("MqttServiceHandler", "Error publishing message to $topic", e)
            }
        }
    }
}
