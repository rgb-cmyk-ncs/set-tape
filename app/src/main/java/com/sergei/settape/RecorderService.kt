package com.sergei.settape

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the app alive with the screen off or while another app is in front. It does no audio
 * work itself: it shows the ongoing notification, holds a wake lock while armed or recording,
 * and goes away when the [Engine] closes its input.
 */
class RecorderService : Service(), Engine.Listener {

    private var wakeLock: PowerManager.WakeLock? = null
    private var shown: Engine.State? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Shown while SetTape is listening or recording"
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
        Engine.addListener(this)
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Engine.stopTake()
            if (Engine.state == Engine.State.IDLE) stopSelf()
            return START_NOT_STICKY
        }
        try {
            val note = buildNotification(Engine.state)
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTE_ID, note)
            }
            shown = Engine.state
        } catch (e: Exception) {
            // Android only allows this while the app is on screen and has the microphone permission.
            stopSelf()
            return START_NOT_STICKY
        }
        sync()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        Engine.removeListener(this)
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onEngineChanged() = sync()
    override fun onEngineMessage(message: String) {}
    override fun onRecordingsChanged() {}

    private fun sync() {
        val state = Engine.state
        if (state == Engine.State.IDLE) {
            releaseWakeLock()
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
            return
        }
        if (state == Engine.State.MONITOR && !Engine.uiVisible) {
            // A take ended while the app was off screen: let go of the input instead of
            // listening for nothing. Closing it brings us straight back here as IDLE.
            Engine.close()
            return
        }
        if (state == Engine.State.ARMED || state == Engine.State.RECORDING) acquireWakeLock() else releaseWakeLock()
        if (state != shown) {
            shown = state
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try {
                nm.notify(NOTE_ID, buildNotification(state))
            } catch (e: Exception) { }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SetTape:recording").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) { }
        wakeLock = null
    }

    private fun buildNotification(state: Engine.State): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val source = Engine.info?.deviceLabel ?: "input"
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
        when (state) {
            Engine.State.RECORDING -> {
                b.setContentTitle("Recording")
                b.setContentText(source)
                b.setShowWhen(true)
                b.setWhen(System.currentTimeMillis() - Engine.takeSeconds * 1000)
                b.setUsesChronometer(true)
            }
            Engine.State.ARMED -> {
                b.setContentTitle("Armed")
                b.setContentText("Waiting for sound from $source")
            }
            else -> {
                b.setContentTitle("Listening")
                b.setContentText(source)
            }
        }
        if (state == Engine.State.ARMED || state == Engine.State.RECORDING) {
            val stop = PendingIntent.getService(
                this, 1,
                Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "Stop", stop).build()
            )
        }
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    companion object {
        private const val CHANNEL = "recorder"
        private const val NOTE_ID = 1
        const val ACTION_STOP = "com.sergei.settape.STOP"

        /** True while the service exists, so the screen knows whether it still needs starting. */
        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, RecorderService::class.java))
            } catch (e: Exception) {
                // Refused when the app is not in the foreground; recording still works on screen.
            }
        }
    }
}
