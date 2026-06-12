package com.example.data.repository

import com.example.data.db.AppDao
import com.example.data.model.AppConfig
import com.example.data.model.MediaHistory
import kotlinx.coroutines.flow.Flow

class AppRepository(private val appDao: AppDao) {

    val appConfig: Flow<AppConfig?> = appDao.getAppConfigFlow()

    val mediaHistory: Flow<List<MediaHistory>> = appDao.getMediaHistoryFlow()

    suspend fun getAppConfigSuspend(): AppConfig? {
        return appDao.getAppConfigSuspend()
    }

    suspend fun saveAppConfig(config: AppConfig) {
        appDao.insertAppConfig(config)
    }

    suspend fun logMediaHistory(item: MediaHistory) {
        appDao.insertMediaHistory(item)
    }

    suspend fun clearHistory() {
        appDao.clearMediaHistory()
    }
}
