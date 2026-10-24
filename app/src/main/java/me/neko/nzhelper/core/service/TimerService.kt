package me.neko.nzhelper.core.service

import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.app.NotificationCompat
import androidx.core.graphics.toColorInt
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
import me.neko.nzhelper.core.datastore.TimerSettings
import me.neko.nzhelper.core.notification.NotificationUtil
import me.neko.nzhelper.core.util.formatTime
import kotlin.math.abs
import kotlin.math.roundToInt
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

    /** 计时悬浮窗视图，null 表示未显示 */
    private var floatingView: FloatingTimerView? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    private var floatingSide: TimerSettings.Side = TimerSettings.Side.RIGHT
    private var floatingLandscape: Boolean = false
    private var lastSafeRect: SafeRect? = null
    private var lastFloatingWidth: Int = 0
    private var snapAnimator: ValueAnimator? = null
    private var dragging: Boolean = false
    private var sessionActive: Boolean = false
    private var displayListener: DisplayManager.DisplayListener? = null
    private var configCallbacks: ComponentCallbacks? = null

    override fun onBind(intent: Intent): IBinder = binder

    inner class LocalBinder : Binder() {
        fun getService(): TimerService = this@TimerService
    }

    /** 让运行中的 Service 立即响应悬浮窗开关变化，而不是等到下一次开始计时 */
    fun applyFloatingPreference() {
        if (!sessionActive) return
        if (TimerSettings.isFloatingEnabled(this) && Settings.canDrawOverlays(this)) {
            showFloatingWindow()
        } else {
            removeFloatingWindow()
        }
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

        sessionActive = true
        _isRunning.value = true
        baseTimeMs = SystemClock.elapsedRealtime()

        tickJob?.cancel()
        tickJob = serviceScope.launch {
            while (isActive) {
                val currentSec =
                    accumulatedSec + ((SystemClock.elapsedRealtime() - baseTimeMs) / 1000).toInt()
                if (_elapsedSec.value != currentSec) {
                    _elapsedSec.value = currentSec
                    updateFloatingWindow()
                }
                delay((1000L - (SystemClock.elapsedRealtime() % 1000)).milliseconds)
            }
        }

        startForeground(NOTIF_ID, buildNotification(_elapsedSec.value))
        showFloatingWindow()
    }

    private fun pauseTimer() {
        if (!_isRunning.value) return

        _isRunning.value = false
        tickJob?.cancel()

        accumulatedSec = _elapsedSec.value
        baseTimeMs = 0L

        updateNotification(accumulatedSec)
        updateFloatingWindow()
    }

    private fun stopTimer() {
        tickJob?.cancel()
        sessionActive = false
        _isRunning.value = false
        accumulatedSec = 0
        _elapsedSec.value = 0
        baseTimeMs = 0L

        removeFloatingWindow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun resetTimer() {
        tickJob?.cancel()
        sessionActive = false
        _isRunning.value = false
        accumulatedSec = 0
        _elapsedSec.value = 0
        baseTimeMs = 0L

        removeFloatingWindow()
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun showFloatingWindow() {
        if (!TimerSettings.isFloatingEnabled(this)) return
        if (!Settings.canDrawOverlays(this)) return
        if (floatingView != null) return
        val wm = windowManager() ?: return

        val view = FloatingTimerView(this).apply {
            text = formatTime(_elapsedSec.value)
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor("#B3202020".toColorInt())
            }
            onTap = {
                val intent = Intent(this@TimerService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                try {
                    this@TimerService.startActivity(intent)
                } catch (_: Exception) {
                }
            }
        }
        measureFloating(view)

        val rect = safeRect()
        val landscape = isLandscape()
        val placement = TimerSettings.getFloatingPlacement(this, landscape)
            ?: TimerSettings.FloatingPlacement(
                TimerSettings.Side.RIGHT,
                rect.top + edgeMargin() + dp(DEFAULT_TOP_INSET_DP)
            )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        floatingParams = params
        floatingLandscape = landscape
        placeInto(params, placement, view, rect)

        attachFloatingTouch(view, params, wm)
        attachFloatingLayoutListener(view)

        try {
            wm.addView(view, params)
            floatingView = view
            registerScreenListeners()
        } catch (e: Exception) {
            floatingParams = null
            e.printStackTrace()
        }
    }

    private fun measureFloating(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        (view.background as? GradientDrawable)?.cornerRadius = view.measuredHeight / 2f
        lastFloatingWidth = view.measuredWidth
    }

    /** 把一份位置档案换算成绝对坐标并写入 params，是所有坐标写入的唯一入口 */
    private fun placeInto(
        params: WindowManager.LayoutParams,
        placement: TimerSettings.FloatingPlacement,
        view: View,
        rect: SafeRect
    ) {
        val margin = edgeMargin()
        val targetX = when (placement.side) {
            TimerSettings.Side.LEFT -> rect.left + margin
            TimerSettings.Side.RIGHT -> rect.right - margin - view.measuredWidth
        }
        val point = clamp(targetX, placement.y, view.measuredWidth, view.measuredHeight, rect)
        params.x = point.x
        params.y = point.y
        floatingSide = placement.side
        lastSafeRect = rect
    }

    private fun applyPlacement(placement: TimerSettings.FloatingPlacement) {
        val view = floatingView ?: return
        val params = floatingParams ?: return
        val wm = windowManager() ?: return
        measureFloating(view)
        placeInto(params, placement, view, safeRect())
        try {
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun clamp(x: Int, y: Int, width: Int, height: Int, rect: SafeRect): Point {
        val margin = edgeMargin()
        val minX = rect.left + margin
        val maxX = (rect.right - margin - width).coerceAtLeast(minX)
        val minY = rect.top + margin
        val maxY = (rect.bottom - margin - height).coerceAtLeast(minY)
        return Point(x.coerceIn(minX, maxX), y.coerceIn(minY, maxY))
    }

    private fun attachFloatingTouch(
        view: FloatingTimerView,
        params: WindowManager.LayoutParams,
        wm: WindowManager
    ) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var downX = 0
        var downY = 0
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    snapAnimator = null
                    dragging = true
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = params.x
                    downY = params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        val rect = safeRect()
                        val point = clamp(
                            (downX + dx).toInt(),
                            (downY + dy).toInt(),
                            view.measuredWidth,
                            view.measuredHeight,
                            rect
                        )
                        params.x = point.x
                        params.y = point.y
                        lastSafeRect = rect
                        try {
                            wm.updateViewLayout(view, params)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    dragging = false
                    if (moved) snapToEdge(view, params, wm) else view.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    // 旋转会中断手势，此时按新方向重新摆放，避免停在旧方向的坐标上
                    onScreenGeometryChanged()
                    true
                }

                else -> false
            }
        }
    }

    private fun snapToEdge(
        view: FloatingTimerView,
        params: WindowManager.LayoutParams,
        wm: WindowManager
    ) {
        val rect = safeRect()
        val margin = edgeMargin()
        val center = params.x + view.measuredWidth / 2
        val side = if (center < (rect.left + rect.right) / 2) {
            TimerSettings.Side.LEFT
        } else {
            TimerSettings.Side.RIGHT
        }
        val targetX = when (side) {
            TimerSettings.Side.LEFT -> rect.left + margin
            TimerSettings.Side.RIGHT -> rect.right - margin - view.measuredWidth
        }
        val point = clamp(targetX, params.y, view.measuredWidth, view.measuredHeight, rect)

        floatingSide = side
        TimerSettings.setFloatingPlacement(
            this,
            floatingLandscape,
            TimerSettings.FloatingPlacement(side, point.y)
        )
        animateTo(point.x, point.y, view, params, wm)
    }

    private fun animateTo(
        targetX: Int,
        targetY: Int,
        view: View,
        params: WindowManager.LayoutParams,
        wm: WindowManager
    ) {
        snapAnimator?.cancel()
        params.y = targetY
        if (params.x == targetX) {
            try {
                wm.updateViewLayout(view, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return
        }
        snapAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = SNAP_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                params.x = animator.animatedValue as Int
                try {
                    wm.updateViewLayout(view, params)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            start()
        }
    }

    private fun attachFloatingLayoutListener(view: View) {
        view.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width <= 0 || width == lastFloatingWidth) return@addOnLayoutChangeListener
            lastFloatingWidth = width
            if (dragging || snapAnimator?.isRunning == true) return@addOnLayoutChangeListener
            val params = floatingParams ?: return@addOnLayoutChangeListener
            val wm = windowManager() ?: return@addOnLayoutChangeListener
            placeInto(
                params,
                TimerSettings.FloatingPlacement(floatingSide, params.y),
                v,
                safeRect()
            )
            try {
                wm.updateViewLayout(v, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updateFloatingWindow() {
        val view = floatingView ?: return
        val elapsed = _elapsedSec.value
        val running = _isRunning.value
        view.text = formatTime(elapsed)
        view.setTextColor(if (running) Color.WHITE else "#B3FFFFFF".toColorInt())
    }

    private fun removeFloatingWindow() {
        snapAnimator?.cancel()
        snapAnimator = null
        dragging = false
        unregisterScreenListeners()
        val view = floatingView
        floatingView = null
        floatingParams = null
        lastFloatingWidth = 0
        lastSafeRect = null
        if (view == null) return
        try {
            windowManager()?.removeView(view)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun edgeMargin(): Int = dp(EDGE_MARGIN_DP)

    private fun windowManager(): WindowManager? =
        getSystemService(WINDOW_SERVICE) as? WindowManager

    private fun safeRect(): SafeRect {
        val wm = windowManager() ?: return SafeRect(
            0,
            0,
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val bounds = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return SafeRect(
                bounds.left + insets.left,
                bounds.top + insets.top,
                bounds.right - insets.right,
                bounds.bottom - insets.bottom
            )
        }
        return legacySafeRect(wm)
    }

    @Suppress("DEPRECATION")
    private fun legacySafeRect(wm: WindowManager): SafeRect {
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        val navRight = if (isLandscape()) systemDimension("navigation_bar_width") else 0
        val navBottom = if (isLandscape()) 0 else systemDimension("navigation_bar_height")
        return SafeRect(
            0,
            systemDimension("status_bar_height"),
            metrics.widthPixels - navRight,
            metrics.heightPixels - navBottom
        )
    }

    /** 系统尺寸资源不可读时返回 0：宁可少避让，也不能算出越界坐标 */
    private fun systemDimension(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        if (id == 0) return 0
        return resources.getDimensionPixelSize(id)
    }

    private fun registerScreenListeners() {
        if (displayListener != null) return
        val displayManager = getSystemService(DISPLAY_SERVICE) as? DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) onScreenGeometryChanged()
            }
        }
        displayManager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        displayListener = listener

        val callbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                onScreenGeometryChanged()
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onLowMemory() = Unit
        }
        applicationContext.registerComponentCallbacks(callbacks)
        configCallbacks = callbacks
    }

    private fun unregisterScreenListeners() {
        displayListener?.let { listener ->
            (getSystemService(DISPLAY_SERVICE) as? DisplayManager)?.unregisterDisplayListener(listener)
        }
        displayListener = null
        configCallbacks?.let { applicationContext.unregisterComponentCallbacks(it) }
        configCallbacks = null
    }

    private fun onScreenGeometryChanged() {
        val view = floatingView ?: return
        if (dragging) return

        val oldRect = lastSafeRect
        val currentY = floatingParams?.y ?: return
        measureFloating(view)

        val landscape = isLandscape()
        if (landscape == floatingLandscape) {
            val stored = TimerSettings.getFloatingPlacement(this, landscape)
            if (stored != null) {
                applyPlacement(stored)
            } else {
                applyPlacement(TimerSettings.FloatingPlacement(floatingSide, currentY))
            }
            return
        }

        val stored = TimerSettings.getFloatingPlacement(this, landscape)
        floatingLandscape = landscape
        if (stored != null) {
            applyPlacement(stored)
        } else {
            val inherited = inheritPlacement(currentY, oldRect, view)
            TimerSettings.setFloatingPlacement(this, landscape, inherited)
            applyPlacement(inherited)
        }
    }

    /** 目标方向还没有档案时，按垂直比例把当前位置搬过去 */
    private fun inheritPlacement(
        currentY: Int,
        oldRect: SafeRect?,
        view: View
    ): TimerSettings.FloatingPlacement {
        val margin = edgeMargin()
        val newRect = safeRect()
        val oldHeight = oldRect?.height ?: newRect.height
        val oldTop = oldRect?.top ?: newRect.top
        val oldSpan = (oldHeight - margin * 2 - view.measuredHeight).coerceAtLeast(1)
        val relative = ((currentY - oldTop - margin).toFloat() / oldSpan).coerceIn(0f, 1f)
        val newSpan = (newRect.height - margin * 2 - view.measuredHeight).coerceAtLeast(1)
        val y = newRect.top + margin + (relative * newSpan).roundToInt()
        return TimerSettings.FloatingPlacement(floatingSide, y)
    }

    override fun onDestroy() {
        tickJob?.cancel()
        serviceScope.cancel()
        removeFloatingWindow()
        super.onDestroy()
    }

    private data class SafeRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val height: Int get() = bottom - top
    }

    companion object {
        const val ACTION_START = "me.neko.nzhelper.ACTION_START"
        const val ACTION_PAUSE = "me.neko.nzhelper.ACTION_PAUSE"
        const val ACTION_STOP = "me.neko.nzhelper.ACTION_STOP"
        const val ACTION_RESET = "me.neko.nzhelper.ACTION_RESET"
        const val NOTIF_ID = 1001
        private const val EDGE_MARGIN_DP = 8
        private const val DEFAULT_TOP_INSET_DP = 96
        private const val SNAP_DURATION_MS = 120L
    }
}

private class FloatingTimerView(context: Context) : AppCompatTextView(context) {
    var onTap: (() -> Unit)? = null

    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }
}
