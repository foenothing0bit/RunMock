package com.example.mockloc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * 前台服务：把轨迹插值结果每秒写入系统的测试位置提供者。
 *
 * 关键点（改代码时容易踩）：
 * - 必须先被用户选为「模拟位置信息应用」，否则 addTestProvider 抛 SecurityException；
 * - 每条 Location 必须设置 time 与 elapsedRealtimeNanos，否则系统直接拒绝；
 * - GPS 与 NETWORK 两个 provider 都写，兼容只读 fused 或只读 network 的 App；
 * - 循环里用真实经过的时间推进，别假设 delay(1000) 精确。
 */
class MockLocationService : Service() {

    companion object {
        private const val TAG = "MockLocationService"

        const val ACTION_START = "com.example.mockloc.action.START"
        const val ACTION_PAUSE = "com.example.mockloc.action.PAUSE"
        const val ACTION_RESUME = "com.example.mockloc.action.RESUME"
        const val ACTION_STOP = "com.example.mockloc.action.STOP"

        const val EXTRA_PACE_SEC_PER_KM = "extra_pace"
        const val EXTRA_TIMED = "extra_timed"
        const val EXTRA_MULTIPLIER = "extra_multiplier"
        const val EXTRA_LOOP = "extra_loop"

        private const val CHANNEL_ID = "route_simulation"
        private const val NOTIFICATION_ID = 1001
        private const val TICK_MS = 1000L

        private val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )

        fun startIntent(
            context: Context,
            paceSecPerKm: Double,
            timed: Boolean,
            multiplier: Double,
            loop: Boolean
        ): Intent = Intent(context, MockLocationService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_PACE_SEC_PER_KM, paceSecPerKm)
            putExtra(EXTRA_TIMED, timed)
            putExtra(EXTRA_MULTIPLIER, multiplier)
            putExtra(EXTRA_LOOP, loop)
        }

        fun controlIntent(context: Context, action: String): Intent =
            Intent(context, MockLocationService::class.java).setAction(action)
    }

    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null

    private var simulator: RouteSimulator? = null
    private var loopMode = false
    private var paused = false
    private var lastTickElapsed = 0L
    private var lastSample: Sample? = null

    private val registered = mutableListOf<String>()

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val started = startInForeground(
                    buildNotification(getString(R.string.notif_title), "正在启动…")
                )
                if (!started || !startSimulation(intent)) {
                    stopSelf()
                }
            }

            ACTION_PAUSE -> {
                paused = true
                MockState.paused = true
                MockState.status = "已暂停"
                refreshNotification()
            }

            ACTION_RESUME -> {
                paused = false
                MockState.paused = false
                lastTickElapsed = SystemClock.elapsedRealtime()
                MockState.status = statusText(lastSample)
                refreshNotification()
            }

            ACTION_STOP -> stopSelf()

            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        tickJob?.cancel()
        tickJob = null
        unregisterProviders()
        if (MockState.running) {
            MockState.status = "已停止"
        }
        MockState.running = false
        MockState.paused = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 启动流程

    private fun startInForeground(notification: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            // Android 12+ 后台启动限制、Android 14 未授予定位权限都会走到这里
            Log.e(TAG, "startForeground failed", e)
            MockState.status = "前台服务启动失败：${e.javaClass.simpleName}"
            false
        }
    }

    private fun startSimulation(intent: Intent): Boolean {
        val route = MockState.route
        if (route == null || route.points.size < 2) {
            MockState.status = "请先导入一条 GPX 路线"
            return false
        }

        if (!MockPermission.isMockApp(this)) {
            MockState.status = "未在开发者选项中被选为模拟位置信息应用"
            Log.w(TAG, "not selected as mock location app")
            return false
        }

        val timed = intent.getBooleanExtra(EXTRA_TIMED, false) && route.hasTiming
        val pace = intent.getDoubleExtra(EXTRA_PACE_SEC_PER_KM, 330.0).coerceIn(120.0, 1200.0)
        val multiplier = intent.getDoubleExtra(EXTRA_MULTIPLIER, 1.0).coerceIn(0.5, 120.0)
        loopMode = intent.getBooleanExtra(EXTRA_LOOP, false)

        paused = false
        lastSample = null
        simulator = RouteSimulator(
            route = route,
            mode = if (timed) RouteSimulator.Mode.TIMED else RouteSimulator.Mode.PACE,
            paceSecondsPerKm = pace,
            speedMultiplier = multiplier,
            loop = loopMode
        )

        if (!registerProviders()) {
            MockState.status = "无法注册模拟位置提供者（授权或机型限制）"
            return false
        }

        MockState.running = true
        MockState.paused = false
        MockState.totalMeters = route.totalMeters
        MockState.distanceMeters = 0.0
        MockState.elapsedSeconds = 0.0
        MockState.currentPaceSecPerKm = 0.0
        MockState.status = "模拟中"
        lastTickElapsed = SystemClock.elapsedRealtime()

        tickJob?.cancel()
        tickJob = scope.launch { runLoop() }
        return true
    }

    private suspend fun runLoop() {
        val sim = simulator ?: return

        publish(sim.advance(0.0))

        while (coroutineContext.isActive) {
            delay(TICK_MS)

            if (paused) {
                lastTickElapsed = SystemClock.elapsedRealtime()
                continue
            }

            val now = SystemClock.elapsedRealtime()
            // 用真实间隔推进，避免 tick 抖动带来速度误差
            val deltaSeconds = ((now - lastTickElapsed).coerceIn(200L, 5_000L)) / 1000.0
            lastTickElapsed = now

            publish(sim.advance(deltaSeconds))

            if (sim.isFinished) {
                MockState.status = "已到达终点：${Fmt.distanceKm(sim.distanceMeters)}，" +
                    "用时 ${Fmt.duration(sim.elapsedSeconds)}"
                refreshNotification()
                break
            }
        }
    }

    private fun publish(sample: Sample) {
        lastSample = sample
        MockState.distanceMeters = sample.distanceMeters
        MockState.elapsedSeconds = sample.elapsedSeconds
        MockState.currentPaceSecPerKm =
            if (sample.speedMps > 0.4) 1000.0 / sample.speedMps else 0.0
        MockState.status = statusText(sample)
        pushLocation(sample)
        refreshNotification()
    }

    // ------------------------------------------------------------ 测试 Provider

    private fun registerProviders(): Boolean {
        unregisterProviders()
        var any = false
        for (provider in PROVIDERS) {
            val isGps = provider == LocationManager.GPS_PROVIDER
            try {
                removeQuietly(provider)
                locationManager.addTestProvider(
                    provider,
                    !isGps,                                  // requiresNetwork
                    isGps,                                   // requiresSatellite
                    false,                                   // requiresCell
                    false,                                   // hasMonetaryCost
                    true,                                    // supportsAltitude
                    true,                                    // supportsSpeed
                    true,                                    // supportsBearing
                    if (isGps) Criteria.POWER_HIGH else Criteria.POWER_LOW,
                    if (isGps) Criteria.ACCURACY_FINE else Criteria.ACCURACY_COARSE
                )
                locationManager.setTestProviderEnabled(provider, true)
                registered.add(provider)
                any = true
            } catch (e: SecurityException) {
                Log.w(TAG, "addTestProvider denied: $provider", e)
            } catch (e: Exception) {
                Log.w(TAG, "addTestProvider failed: $provider", e)
            }
        }
        return any
    }

    private fun unregisterProviders() {
        for (provider in registered.toList()) {
            try {
                locationManager.setTestProviderEnabled(provider, false)
            } catch (_: Exception) {
                // 忽略：可能已经被系统回收
            }
            removeQuietly(provider)
        }
        registered.clear()
    }

    private fun removeQuietly(provider: String) {
        try {
            locationManager.removeTestProvider(provider)
        } catch (_: Exception) {
            // 忽略
        }
    }

    private fun pushLocation(sample: Sample) {
        val wallTime = System.currentTimeMillis()
        val elapsedNanos = SystemClock.elapsedRealtimeNanos()
        var primary: Location? = null

        for (provider in registered.toList()) {
            val isGps = provider == LocationManager.GPS_PROVIDER
            val location = Location(provider).apply {
                latitude = sample.position.lat
                longitude = sample.position.lon
                altitude = sample.altitudeMeters
                accuracy = if (isGps) 3.5f else 18f
                time = wallTime
                elapsedRealtimeNanos = elapsedNanos
                speed = sample.speedMps.toFloat()
                bearing = sample.bearing
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    verticalAccuracyMeters = if (isGps) 2.5f else 8f
                    speedAccuracyMetersPerSecond = if (isGps) 0.25f else 1.5f
                    bearingAccuracyDegrees = if (isGps) 4f else 20f
                }
                if (isGps) {
                    // 部分 App 会读卫星数判断 GPS 是否可信
                    extras = Bundle().apply {
                        putInt("satellites", 14)
                        putInt("maxCn0", 38)
                        putInt("meanCn0", 30)
                    }
                }
            }
            if (primary == null) primary = location
            try {
                locationManager.setTestProviderLocation(provider, location)
            } catch (e: SecurityException) {
                Log.w(TAG, "setTestProviderLocation denied: $provider", e)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "setTestProviderLocation rejected: $provider", e)
            }
        }

        primary?.let { MockState.lastLocation = it }
    }

    private fun statusText(sample: Sample?): String {
        val s = sample ?: return "模拟中"
        val pace = if (s.speedMps > 0.4) 1000.0 / s.speedMps else 0.0
        return "${Fmt.distanceKm(s.distanceMeters)} / ${Fmt.distanceKm(MockState.totalMeters)}" +
            " · ${Fmt.duration(s.elapsedSeconds)} · ${Fmt.paceSecondsToText(pace)}"
    }

    // ------------------------------------------------------------------ 通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleLabel = if (paused) R.string.notif_action_resume else R.string.notif_action_pause
        val toggleAction = if (paused) ACTION_RESUME else ACTION_PAUSE

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(toggleLabel), serviceAction(toggleAction))
            .addAction(0, getString(R.string.notif_action_stop), serviceAction(ACTION_STOP))
            .build()
    }

    private fun serviceAction(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, MockLocationService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun refreshNotification() {
        val text = if (paused) "已暂停" else statusText(lastSample)
        try {
            notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification(getString(R.string.notif_title), text)
            )
        } catch (e: Exception) {
            Log.w(TAG, "notify failed", e)
        }
    }
}
