package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.example.api.ReceiverManager
import com.example.data.db.AppDatabase
import com.example.data.repository.AppRepository
import com.example.service.ReceiverService
import com.example.ui.screens.TvDashboardScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Setup shared ViewModel and DB repository
        val database = AppDatabase.getDatabase(this)
        val repository = AppRepository(database.appDao())
        viewModel = MainViewModel(application, repository)

        // 2. Start the persistent Background Media Receiver Service (Foreground Service)
        val serviceIntent = Intent(this, ReceiverService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // 3. Sync initial system audio parameters
        ReceiverManager.updateSystemVolumeState()

        // 4. Render main Smart Receiver UI
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TvDashboardScreen(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize(),
                        onManualPlayPause = { ReceiverManager.handlePlayPauseIntent() },
                        onManualStop = { ReceiverManager.stopActiveMediaPlayer() }
                    )
                }
            }
        }
    }
}
