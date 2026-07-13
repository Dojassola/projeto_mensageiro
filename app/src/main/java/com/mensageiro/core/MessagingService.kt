package com.mensageiro.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.mensageiro.MainActivity

class MessagingService : Service() {
    private val listener = MessagingRuntime.Listener { snapshot ->
        getSystemService(NotificationManager::class.java).notify(
            ServiceNotificationId,
            Notifications.service(this, snapshot.serviceStatus)
        )
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        val notification = Notifications.service(this, "Iniciando...")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ServiceNotificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(ServiceNotificationId, notification)
        }
        MessagingRuntime.addListener(listener)
        MessagingRuntime.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MessagingRuntime.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        MessagingRuntime.removeListener(listener)
        MessagingRuntime.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ServiceNotificationId = 10

        fun start(context: Context) {
            val intent = Intent(context, MessagingService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}

object Notifications {
    private const val ServiceChannel = "mensageiro_service"
    private const val MessageChannel = "mensageiro_messages"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ServiceChannel, "Conexao do Mensageiro", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(MessageChannel, "Novas mensagens", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun service(context: Context, status: String): Notification =
        builder(context, ServiceChannel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Mensageiro ativo")
            .setContentText(status)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

    fun message(context: Context, contactName: String, text: String): Notification =
        builder(context, MessageChannel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(contactName)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()

    private fun builder(context: Context, channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channel) else Notification.Builder(context)

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
