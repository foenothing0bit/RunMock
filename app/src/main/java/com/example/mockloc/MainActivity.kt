package com.example.mockloc

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.mockloc.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * 路线模拟控制台。
 *
 * 交互只有一条主线：导入 GPX（或示例路线）→ 选配速 / 回放模式 → 开始 → 看进度。
 * 真正的定位注入在 [MockLocationService]，这里只负责参数与展示。
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val DEFAULT_LAT = 39.9932
        const val DEFAULT_LON = 116.3832
        const val CACHE_FILE = "last_route.gpx"
        const val MAX_GPX_BYTES = 20 * 1024 * 1024
        const val DEFAULT_PACE_SEC_PER_KM = 330.0
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var map: MapView
    private lateinit var routeLine: Polyline

    private var route: Route? = null
    private var loading = false
    private var pendingStart = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val poller = object : Runnable {
        override fun run() {
            refreshState()
            uiHandler.postDelayed(this, 500L)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (pendingStart) {
            pendingStart = false
            doStart()
        }
    }

    private val gpxPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importGpx(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        setupMap()
        setupUi()
        restoreRoute()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        refreshState()
        uiHandler.post(poller)
    }

    override fun onPause() {
        map.onPause()
        uiHandler.removeCallbacks(poller)
        super.onPause()
    }

    // -------------------------------------------------------------------- 地图

    private fun setupMap() {
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setBuiltInZoomControls(false)
            controller.setZoom(14.0)
            controller.setCenter(GeoPoint(DEFAULT_LAT, DEFAULT_LON))
        }

        routeLine = Polyline(map).apply {
            outlinePaint.color = ContextCompat.getColor(this@MainActivity, R.color.accent)
            outlinePaint.strokeWidth = 8f
        }
        map.overlays.add(routeLine)

        // 地图嵌在 ScrollView 里，需要自己抢手势，否则上下滑动会被父容器吃掉
        map.setOnTouchListener { view, _ ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        binding.mapContainer.addView(
            map,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    // ------------------------------------------------------------------ 交互

    private fun setupUi() {
        binding.inputPace.setText("5:30")
        binding.inputMultiplier.setText("10")
        binding.modeGroup.check(R.id.btnModePace)

        binding.btnImportGpx.setOnClickListener { gpxPicker.launch(arrayOf("*/*")) }
        binding.btnSample.setOnClickListener { setRoute(buildSampleRoute()) }
        binding.btnClear.setOnClickListener { clearRoute() }

        binding.btnOpenDevSettings.setOnClickListener {
            MockPermission.openSettings(this)
            toast("请选择「模拟位置信息应用」→「跑步轨迹模拟」")
        }

        binding.btnStartStop.setOnClickListener {
            if (MockState.running) stopSimulation() else requestStart()
        }

        binding.modeGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) updateModeUi()
        }
        updateModeUi()
    }

    private fun isTimedMode(): Boolean = binding.modeGroup.checkedButtonId == R.id.btnModeTimed

    private fun updateModeUi() {
        val timed = isTimedMode()
        binding.inputPace.isEnabled = !timed
        binding.inputMultiplier.isEnabled = timed
        refreshRouteInfo()
    }

    // ------------------------------------------------------------------ 路线

    private fun restoreRoute() {
        val inMemory = MockState.route
        if (inMemory != null) {
            route = inMemory
            MockState.totalMeters = inMemory.totalMeters
            renderRoute()
            refreshRouteInfo()
            return
        }
        loadCachedRoute()
    }

    private fun loadCachedRoute() {
        val file = File(filesDir, CACHE_FILE)
        if (!file.exists()) return

        loading = true
        refreshRouteInfo()
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching { file.inputStream().use { GpxParser.parse(it) } }.getOrNull()
            }
            loading = false
            if (parsed != null) {
                setRoute(parsed, announce = false)
            } else {
                refreshRouteInfo()
            }
        }
    }

    private fun importGpx(uri: Uri) {
        if (MockState.running) {
            toast("请先停止当前模拟")
            return
        }
        loading = true
        refreshRouteInfo()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = contentResolver.openInputStream(uri)?.use { readAll(it) }
                        ?: throw IllegalStateException("无法打开所选文件")
                    File(filesDir, CACHE_FILE).writeBytes(bytes)
                    GpxParser.parse(bytes.inputStream())
                }
            }
            loading = false
            result
                .onSuccess { setRoute(it) }
                .onFailure {
                    refreshRouteInfo()
                    toast("导入失败：${it.message ?: "未知错误"}")
                }
        }
    }

    private fun readAll(input: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream(1024 * 256)
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > MAX_GPX_BYTES) {
                throw IllegalStateException("GPX 文件超过 20 MB，请先抽稀")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun setRoute(newRoute: Route, announce: Boolean = true) {
        route = newRoute
        MockState.route = newRoute
        MockState.totalMeters = newRoute.totalMeters
        MockState.distanceMeters = 0.0
        MockState.elapsedSeconds = 0.0
        renderRoute()
        refreshRouteInfo()
        if (announce) toast("已加载：${newRoute.name}")
    }

    private fun clearRoute() {
        if (MockState.running) {
            toast("请先停止当前模拟")
            return
        }
        route = null
        MockState.route = null
        MockState.totalMeters = 0.0
        MockState.distanceMeters = 0.0
        MockState.elapsedSeconds = 0.0
        routeLine.setPoints(ArrayList<GeoPoint>())
        File(filesDir, CACHE_FILE).delete()
        refreshRouteInfo()
        refreshState()
    }

    private fun renderRoute() {
        val current = route ?: return
        val geoPoints = ArrayList<GeoPoint>(current.points.size)
        for (point in current.points) {
            geoPoints.add(GeoPoint(point.lat, point.lon))
        }
        routeLine.setPoints(geoPoints)

        if (geoPoints.size >= 2) {
            val north = geoPoints.maxOf { it.latitude }
            val south = geoPoints.minOf { it.latitude }
            val east = geoPoints.maxOf { it.longitude }
            val west = geoPoints.minOf { it.longitude }
            map.zoomToBoundingBox(BoundingBox(north, east, south, west), true, 64)
        } else if (geoPoints.size == 1) {
            map.controller.setZoom(16.0)
            map.controller.setCenter(geoPoints[0])
        }
        map.invalidate()
    }

    private fun refreshRouteInfo() {
        if (loading) {
            binding.routeInfo.text = getString(R.string.route_loading)
            return
        }
        val current = route
        if (current == null) {
            binding.routeInfo.text = getString(R.string.route_empty)
            return
        }

        val pace = parsePace(binding.inputPace.text?.toString().orEmpty()) ?: DEFAULT_PACE_SEC_PER_KM
        val expectedSeconds = current.totalMeters / 1000.0 * pace
        val gain = current.elevationGainMeters()

        binding.routeInfo.text = buildString {
            append(current.name)
            append('\n')
            append(
                String.format(
                    Locale.US,
                    "%d 个点 · %.2f km",
                    current.points.size,
                    current.totalMeters / 1000.0
                )
            )
            if (gain > 1.0) {
                append(String.format(Locale.US, " · 爬升 %d m", gain.toInt()))
            }
            append('\n')
            append("按 ${Fmt.paceSecondsToText(pace)} 配速约需 ${Fmt.duration(expectedSeconds)}")
            if (current.hasTiming) {
                append(" · 原始用时 ${Fmt.duration(current.timedDurationMs / 1000.0)}")
            }
        }
    }

    /** "5:30" / "5.5"（分钟）-> 秒/公里；非法返回 null。 */
    private fun parsePace(raw: String): Double? {
        val text = raw.trim().replace('：', ':')
        if (text.isEmpty()) return null
        return try {
            if (text.contains(':')) {
                val parts = text.split(':')
                if (parts.size != 2) return null
                val minutes = parts[0].trim().toIntOrNull() ?: return null
                val seconds = parts[1].trim().toIntOrNull() ?: return null
                if (seconds !in 0..59) return null
                return (minutes * 60 + seconds).toDouble().takeIf { it > 0.0 }
            }
            val minutes = text.toDoubleOrNull() ?: return null
            (minutes * 60.0).takeIf { it > 0.0 }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ 开关

    private fun requestStart() {
        val current = route
        if (current == null || current.points.size < 2) {
            toast("请先导入 GPX 或加载示例路线")
            return
        }
        if (isTimedMode() && !current.hasTiming) {
            toast("这条轨迹没有时间戳，请改用「按配速跑」")
            return
        }
        if (!MockPermission.isMockApp(this)) {
            toast("请先在 开发者选项 → 选择模拟位置信息应用 中选中本应用")
            MockPermission.openSettings(this)
            return
        }

        val missing = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) {
            pendingStart = true
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        doStart()
    }

    private fun doStart() {
        if (route == null) return
        val pace = (parsePace(binding.inputPace.text?.toString().orEmpty())
            ?: DEFAULT_PACE_SEC_PER_KM).coerceIn(120.0, 1200.0)
        val multiplier = (binding.inputMultiplier.text?.toString()?.toDoubleOrNull() ?: 10.0)
            .coerceIn(0.5, 120.0)

        val intent = MockLocationService.startIntent(
            context = this,
            paceSecPerKm = pace,
            timed = isTimedMode(),
            multiplier = multiplier,
            loop = binding.switchLoop.isChecked
        )
        try {
            ContextCompat.startForegroundService(this, intent)
            toast("开始模拟")
        } catch (e: Exception) {
            toast("启动失败：${e.message}")
        }
    }

    private fun stopSimulation() {
        try {
            // 服务已在运行，用普通 startService 即可；用 startForegroundService 会因
            // 没有在 5 秒内调用 startForeground 而触发 ForegroundServiceDidNotStartInTimeException
            startService(MockLocationService.controlIntent(this, MockLocationService.ACTION_STOP))
        } catch (e: Exception) {
            toast("停止失败：${e.message}")
        }
    }

    // ------------------------------------------------------------------ 状态

    private fun refreshState() {
        val authorized = MockPermission.isMockApp(this)
        val running = MockState.running

        binding.btnStartStop.setText(if (running) R.string.btn_stop else R.string.btn_start)
        binding.btnStartStop.isEnabled = route != null && !loading
        binding.btnImportGpx.isEnabled = !running
        binding.btnSample.isEnabled = !running
        binding.btnClear.isEnabled = !running

        val percent = if (MockState.totalMeters > 0.0) {
            (MockState.distanceMeters / MockState.totalMeters * 100).toInt().coerceIn(0, 100)
        } else {
            0
        }
        binding.progress.setProgressCompat(percent, true)

        val pace = if (MockState.currentPaceSecPerKm > 0.0) {
            Fmt.paceSecondsToText(MockState.currentPaceSecPerKm)
        } else {
            "--'--\""
        }
        binding.statsText.text = String.format(
            Locale.US,
            "已跑 %.2f / %.2f km · 用时 %s · 当前 %s",
            MockState.distanceMeters / 1000.0,
            MockState.totalMeters / 1000.0,
            Fmt.duration(MockState.elapsedSeconds),
            pace
        )

        val headline = if (authorized) {
            getString(R.string.status_authorized)
        } else {
            getString(R.string.status_unauthorized)
        }
        binding.statusText.text = buildString {
            append(headline)
            append('\n')
            append(MockState.status.ifBlank { getString(R.string.status_idle) })
        }
        binding.statusText.setTextColor(
            ContextCompat.getColor(this, if (authorized) R.color.status_ok else R.color.status_warn)
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ 示例

    /** 程序生成的椭圆环线，约 3 km，带回环与起伏，用于没带 GPX 时冒烟测试。 */
    private fun buildSampleRoute(): Route {
        val centerLat = DEFAULT_LAT
        val centerLon = DEFAULT_LON
        val latRadius = 0.0045
        val lonRadius = 0.0058
        val steps = 240
        val points = ArrayList<RoutePoint>(steps + 1)

        for (i in 0..steps) {
            val angle = 2.0 * Math.PI * i / steps
            val lat = centerLat + latRadius * Math.sin(angle)
            val lon = centerLon + lonRadius * Math.cos(angle) * (1.0 + 0.18 * Math.sin(3 * angle))
            val elevation = 45.0 + 4.0 * Math.sin(2 * angle)
            points.add(RoutePoint(lat, lon, elevation, i * 8_000L))
        }
        return Route("示例环线（程序生成）", points)
    }
}
