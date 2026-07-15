package com.mensageiro.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mensageiro.MainActivity
import com.mensageiro.R

class CallService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ChannelId, "Chamadas", NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        startForeground(
            NotificationId,
            NotificationCompat.Builder(this, ChannelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Chamada em andamento")
                .setContentText(intent?.getStringExtra(ContactName).orEmpty())
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        )
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ChannelId = "mensageiro_calls"
        private const val NotificationId = 3_001
        private const val ContactName = "contact_name"

        fun start(context: Context, contactName: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallService::class.java).putExtra(ContactName, contactName)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
