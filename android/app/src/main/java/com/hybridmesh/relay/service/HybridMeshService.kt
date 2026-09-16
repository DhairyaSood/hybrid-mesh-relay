package com.hybridmesh.relay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.hybridmesh.relay.messaging.MessagingManager
import com.hybridmesh.relay.network.NetworkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class HybridMeshService : Service() {

    private lateinit var networkManager: NetworkManager
    private lateinit var messagingManager: MessagingManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startMeshForeground()

        networkManager =
            NetworkManager.getInstance(this)

        messagingManager =
            MessagingManager.getInstance(this)

        // The service is the sole owner of the long-lived mesh runtime.
        // Prepare the GATT server before advertising when Bluetooth is already
        // usable, then start the always-on runtime. If Bluetooth is currently
        // unavailable, NetworkManager will recover it when state changes.
        serviceScope.launch {
            // The service owns the runtime lifecycle. NetworkManager drives the
            // BLE recovery transaction and delegates only the GATT server
            // implementation to MessagingManager.
            messagingManager.start()
            networkManager.setGattLifecycle(
                start = { messagingManager.prepareGattServer() },
                stop = { messagingManager.stopGattServer() }
            )
            networkManager.startRuntime()
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        networkManager.stopRuntime()
        messagingManager.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null

    @Suppress("DEPRECATION")
    private fun startMeshForeground() {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val serviceType =
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            super.startForeground(
                NOTIFICATION_ID,
                notification,
                serviceType
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            super.startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            super.startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable.stat_sys_data_bluetooth
            )
            .setContentTitle(
                "Neyra"
            )
            .setContentText(
                "Mesh communication is active in the background"
            )
            .setOngoing(true)
            .setCategory(
                Notification.CATEGORY_SERVICE
            )
            .setShowWhen(false)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Mesh communication",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Keeps Neyra available for nearby communication and queued message delivery."

                setShowBadge(false)
            }

        manager.createNotificationChannel(channel)
    }

    companion object {

        private const val CHANNEL_ID =
            "hybrid_mesh_runtime"

        private const val NOTIFICATION_ID =
            7001

        fun start(context: Context) {
            val intent =
                Intent(
                    context.applicationContext,
                    HybridMeshService::class.java
                )

            ContextCompat.startForegroundService(
                context.applicationContext,
                intent
            )
        }
    }
}