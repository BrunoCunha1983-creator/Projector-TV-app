package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.api.ReceiverManager
import com.example.data.db.AppDatabase
import com.example.data.repository.AppRepository

class ReceiverService : Service() {

    override fun onCreate() {
        super.onCreate()
        Log.d("ReceiverService", "onCreate: Starting persistent background media receiver service")
        
        // 1. Initialize databases and repository references
        val database = AppDatabase.getDatabase(this)
        val repository = AppRepository(database.appDao())
        
        // 2. Initialize the global thread-safe state and receiver orchestrator singleton
        ReceiverManager.init(applicationContext, repository)
        
        // 3. Keep the started service running by transitioning to foreground
        startServiceInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("ReceiverService", "onStartCommand: background media receiver service is alive and listening")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        Log.d("ReceiverService", "onDestroy: shutting down background receiver service")
        ReceiverManager.destroy()
        super.onDestroy()
    }

    private fun startServiceInForeground() {
        val channelId = "media_receiver_channel"
        val notificationId = 1883
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Smart TV Receiver",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém os servidores mDNS, API HTTP e MQTT ativos em segundo plano"
            }
            notificationManager.createNotificationChannel(channel)
        }
        
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)
        
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            // Localized UI and user feedback matching the rest of the application
            .setContentTitle("Receptor de TV Inteligente")
            .setContentText("Integrado e escutando comandos do Home Assistant")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
            
        try {
            startForeground(notificationId, notification)
        } catch (e: Exception) {
            Log.e("ReceiverService", "Failed to startForeground media service wrapper", e)
        }
    }
}
