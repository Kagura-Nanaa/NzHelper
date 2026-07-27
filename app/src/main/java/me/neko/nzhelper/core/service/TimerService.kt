package me.neko.nzhelper.core.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.neko.nzhelper.MainActivity
import me.neko.nzhelper.R
import me.neko.nzhelper.core.notification.NotificationUtil
import me.neko.nzhelper.core.util.formatTime
import kotlin.time.Duration.Companion.milliseconds

/**
 * 前台计时服务
 */
class TimerService : Service() {

    private val binder = LocalBinder()

    private val _elapsedSec = MutableStateFlow(0)
    val elapsedSec: StateFlow<Int> = _elapsedSec.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var accumulatedSec: Int = 0
    private var baseTimeMs: Long = 0L

    /** 绑定 Service 生命周期的协程作用域，onDestroy 时统一取消 */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 当前计时 tick 协程，暂停/停止/重置时取消 */
    private var tickJob: Job? = null

    override fun onBind(intent: Intent): IBinder = binder

    inner class LocalBinder : Binder() {
        fun getService(): TimerService = this@TimerService
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTimer()
            ACTION_PAUSE -> pauseTimer()
            ACTION_STOP -> stopTimer()
            ACTION_RESET -> resetTimer()
        }
        return START_STICKY
    }

    private fun startTimer() {
        if (_isRunning.value) return

        _isRunning.value = true
        baseTimeMs = SystemClock.elapsedRealtime()

        tickJob?.cancel()
        tickJob = serviceScope.launch {
            while (isActive) {
                val currentSec =
                    accumulatedSec + ((SystemClock.elapsedRealtime() - baseTimeMs) / 1000).toInt()
                if (_elapsedSec.value != currentSec) {
                    _elapsedSec.value = currentSec
                }
                delay((1000L - (SystemClock.elapsedRealtime() % 1000)).milliseconds)
            }
        }

        startForeground(NOTIF_ID, buildNotification(_elapsedSec.value))
    }

    private fun pauseTimer() {
        if (!_isRunning.value) return

        _isRunning.value = false
        tickJob?.cancel()

        accumulatedSec = _elapsedSec.value
        baseTimeMs = 0L

        updateNotification(accumulatedSec)
    }

    private fun stopTimer() {
        tickJob?.cancel()
        _isRunning.value = false
        accumulatedSec = 0
        _elapsedSec.value = 0
        baseTimeMs = 0L

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun resetTimer() {
        tickJob?.cancel()
        _isRunning.value = false
        accumulatedSec = 0
        _elapsedSec.value = 0
        baseTimeMs = 0L

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(elapsed: Int): Notification {
        val isRunning = _isRunning.value

        val builder = NotificationCompat.Builder(this, NotificationUtil.CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_access_alarm_24)
            .setContentIntent(getOpenAppPendingIntent())
            .setOngoing(isRunning)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setShowWhen(true)

        if (isRunning) {
            builder.setContentTitle("计时进行中")
            builder.setUsesChronometer(true)
            builder.setWhen(System.currentTimeMillis() - elapsed * 1000L)
            builder.setChronometerCountDown(false)
        } else {
            builder.setContentTitle("计时已暂停")
            builder.setUsesChronometer(false)
            builder.setContentText(formatTime(elapsed))
            builder.setWhen(System.currentTimeMillis())
        }

        val toggleIcon =
            if (isRunning) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val toggleTitle = if (isRunning) "暂停" else "继续"
        val toggleAction = if (isRunning) ACTION_PAUSE else ACTION_START

        builder.addAction(
            NotificationCompat.Action.Builder(
                toggleIcon,
                toggleTitle,
                getPendingIntent(toggleAction)
            ).build()
        )

        return builder.build()
    }

    private fun getPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, TimerService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_STOP_CONFIRM
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            this,
            MainActivity.ACTION_OPEN_STOP_CONFIRM.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotification(elapsed: Int) {
        val notif = buildNotification(elapsed)
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIF_ID, notif)
    }

    override fun onDestroy() {
        tickJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "me.neko.nzhelper.ACTION_START"
        const val ACTION_PAUSE = "me.neko.nzhelper.ACTION_PAUSE"
        const val ACTION_STOP = "me.neko.nzhelper.ACTION_STOP"
        const val ACTION_RESET = "me.neko.nzhelper.ACTION_RESET"
        const val NOTIF_ID = 1001
    }
}
