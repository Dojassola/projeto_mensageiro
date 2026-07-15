package com.mensageiro.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
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
        val peerId = intent?.getStringExtra(PeerId).orEmpty()
        startForeground(
            NotificationId,
            NotificationCompat.Builder(this, ChannelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Chamada em andamento")
                .setContentText(intent?.getStringExtra(ContactName).orEmpty())
                .setContentIntent(openApp)
                .addAction(
                    0,
                    "Encerrar",
                    CallActionReceiver.pendingIntent(this, peerId, CallActionReceiver.End)
                )
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
        private const val PeerId = "peer_id"

        fun start(context: Context, contactName: String, peerId: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallService::class.java)
                    .putExtra(ContactName, contactName)
                    .putExtra(PeerId, peerId)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val peerId = intent.getStringExtra(PeerId) ?: return
        MessagingRuntime.start(context)
        when (intent.action) {
            Accept -> MessagingRuntime.acceptCall(peerId)
            Reject -> MessagingRuntime.rejectCall(peerId)
            End -> MessagingRuntime.endCall(peerId)
        }
    }

    companion object {
        const val Accept = "com.mensageiro.call.ACCEPT"
        const val Reject = "com.mensageiro.call.REJECT"
        const val End = "com.mensageiro.call.END"
        private const val PeerId = "peer_id"

        fun pendingIntent(context: Context, peerId: String, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                31 * peerId.hashCode() + action.hashCode(),
                Intent(context, CallActionReceiver::class.java)
                    .setAction(action)
                    .putExtra(PeerId, peerId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
