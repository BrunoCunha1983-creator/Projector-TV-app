package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.AppConfig
import com.example.data.model.MediaHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {

    @Query("SELECT * FROM app_config WHERE id = 0 LIMIT 1")
    fun getAppConfigFlow(): Flow<AppConfig?>

    @Query("SELECT * FROM app_config WHERE id = 0 LIMIT 1")
    suspend fun getAppConfigSuspend(): AppConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppConfig(appConfig: AppConfig)

    @Query("SELECT * FROM media_history ORDER BY timestamp DESC LIMIT 50")
    fun getMediaHistoryFlow(): Flow<List<MediaHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMediaHistory(item: MediaHistory)

    @Query("DELETE FROM media_history")
    suspend fun clearMediaHistory()
}
