package com.ethran.notable.ink

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.ethran.notable.MainActivity
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Receives the hub's "stream_request" push (reverse initiation) and registers
 * this device's FCM token with the hub. The push carries only the Dropbox path;
 * tapping the notification hands it to [MainActivity], which resolves/imports the
 * notebook and starts streaming (see StreamRequestBus). We deliberately do NOT
 * auto-start on receipt -- the user approves each pull by tapping.
 */
class InkFcmService : FirebaseMessagingService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface InkFcmEntryPoint {
        fun inkStreamClient(): InkStreamClient
    }

    override fun onNewToken(token: String) {
        registerTokenWithHub(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "stream_request") return
        val doc = message.data["doc"] ?: return
        Log.i(TAG, "stream_request for $doc (foreground=${StreamRequestBus.appForeground})")
        if (StreamRequestBus.appForeground) {
            // App is visible: prompt in-app. Opening from here keeps a stable
            // surface (no background->foreground re-init), avoiding the editor
            // page-load race that a notification-tap cold open hits.
            StreamRequestBus.postPrompt(doc)
        } else {
            showStreamNotification(doc)
        }
    }

    private fun showStreamNotification(doc: String) {
        val name = doc.substringAfterLast('/')
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_STREAM_DOC, doc)
        }
        val pi = PendingIntent.getActivity(
            this, doc.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        ensureChannel(this)
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Stream from laptop")
            .setContentText("Open “$name” and stream to xournal")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, notif)
    }

    companion object {
        const val EXTRA_STREAM_DOC = "stream_doc"
        private const val TAG = "InkFcmService"
        private const val CHANNEL_ID = "ink_stream_requests"
        private const val NOTIF_ID = 4711

        fun ensureChannel(ctx: Context) {
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "Ink stream requests",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
        }

        @Volatile
        private var registerJob: Job? = null

        /**
         * Register [token] with the hub using the Ink Stream settings. Settings
         * load ASYNC (InkStreamClient.loadSettings) and the host may be set later
         * without an app restart, so we OBSERVE the settings rather than read them
         * once, and (re-)register whenever a non-blank host appears or changes.
         */
        fun registerTokenWithHub(ctx: Context, token: String) {
            val client = EntryPointAccessors
                .fromApplication(ctx, InkFcmEntryPoint::class.java)
                .inkStreamClient()
            registerJob?.cancel()
            registerJob = CoroutineScope(Dispatchers.IO).launch {
                client.settings
                    .filter { it.host.isNotBlank() }
                    .distinctUntilChanged { a, b ->
                        a.host == b.host && a.hubPort == b.hubPort && a.secret == b.secret
                    }
                    .collect { s ->
                        val ok = InkHubClient.registerPush(s.host, s.hubPort, token, s.secret)
                        Log.i(TAG, "register_push -> $ok (host=${s.host})")
                    }
            }
        }
    }
}
