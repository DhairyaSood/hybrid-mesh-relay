package com.hybridmesh.relay.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hybridmesh.relay.MainActivity
import java.util.Locale

/** Conversation-scoped notification state. Notification denial never touches message persistence. */
class MessagingNotificationCoordinator private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init { createChannel() }

    fun notifyIncoming(peerNodeId: String, displayName: String, body: String) {
        if (!canPostNotifications()) return
        val key = normalize(peerNodeId)
        val nextCount = preferences.getInt("count_$key", 0) + 1
        preferences.edit().putInt("count_$key", nextCount).apply()

        val title = "$displayName — $nextCount new message${if (nextCount == 1) "" else "s"}"
        val contentIntent = PendingIntent.getActivity(
            appContext,
            key.hashCode(),
            Intent(appContext, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PEER_NODE_ID, peerNodeId),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setGroup("neyra_messages")
            .build()

        NotificationManagerCompat.from(appContext).notify(notificationId(key), notification)
    }

    fun clearConversation(peerNodeId: String) {
        val key = normalize(peerNodeId)
        preferences.edit().remove("count_$key").apply()
        NotificationManagerCompat.from(appContext).cancel(notificationId(key))
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Incoming Neyra conversations"
            }
        )
    }

    private fun notificationId(key: String): Int = 8100 + (key.hashCode() and 0x7FFF)
    private fun normalize(value: String): String = value.trim().uppercase(Locale.US)
    private fun pendingIntentImmutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    companion object {
        private const val CHANNEL_ID = "neyra_messages"
        private const val PREFS = "neyra_notification_state"
        @Volatile private var INSTANCE: MessagingNotificationCoordinator? = null

        fun getInstance(context: Context): MessagingNotificationCoordinator =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MessagingNotificationCoordinator(context.applicationContext).also { INSTANCE = it }
            }
    }
}
