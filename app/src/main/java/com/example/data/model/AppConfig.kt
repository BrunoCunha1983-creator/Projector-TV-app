package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_config")
data class AppConfig(
    @PrimaryKey val id: Int = 0,
    val deviceName: String = "Android TV Media Player",
    val serverPort: Int = 8080,
    val mqttBroker: String = "",
    val mqttPort: Int = 1883,
    val mqttUser: String = "",
    val mqttPassword: String = "",
    val mqttEnabled: Boolean = false,
    val haAddress: String = "",
    val haToken: String = "",
    val haEnabled: Boolean = false,
    val projectorUiMode: Boolean = false
)
