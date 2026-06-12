package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_history")
data class MediaHistory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val type: String, // "MEDIA" or "TTS"
    val title: String,
    val subtitle: String,
    val duration: String,
    val timestamp: Long = System.currentTimeMillis()
)
