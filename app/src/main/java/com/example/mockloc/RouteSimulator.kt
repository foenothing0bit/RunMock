package com.example.mockloc

/**
 * 沿 [Route] 推进的模拟器。
 *
 * 两种模式：
 * - [Mode.PACE]：按固定配速跑，与 GPX 自带时间戳无关。适合稳定复现，比如“验证 5'30"/km 时卡路里/配速显示是否正确”。
 * - [Mode.TIMED]：按 GPX 原始时间轴回放，可用 [speedMultiplier] 加速，适合几分钟内跑完一条 10 km 的路线做回归测试。
 *
 * 时间推进一律用调用方传入的真实间隔（秒），不要在内部假设每次恰好 1 秒。
 */
class RouteSimulator(
    private val route: Route,
    val mode: Mode,
    paceSecondsPerKm: Double,
    speedMultiplier: Double,
    private val loop: Boolean
) {

    enum class Mode { PACE, TIMED }

    private val paceSpeedMps: Double =
        if (paceSecondsPerKm > 0.0) 1000.0 / paceSecondsPerKm else 0.0

    private val multiplier: Double = speedMultiplier.coerceIn(0.1, 600.0)

    private var distance = 0.0
    private var elapsed = 0.0
    private var speedMps = 0.0
    private var lastPosition: LatLng = route.points.first().latLng
    private var lastBearing: Float = 0f

    /** 非循环模式下跑到终点后置 true。 */
    var isFinished: Boolean = false
        private set

    val totalMeters: Double get() = route.totalMeters
    val distanceMeters: Double get() = distance
    val elapsedSeconds: Double get() = elapsed

    fun advance(deltaSeconds: Double): Sample {
        val dt = deltaSeconds.coerceIn(0.0, 60.0)
        val before = distance
        var wrapped = false

        when (mode) {
            Mode.PACE -> {
                distance += paceSpeedMps * dt
                elapsed += dt
                val total = totalMeters
                if (total > 0.0 && distance >= total) {
                    if (loop) {
                        distance %= total
                        wrapped = true
                    } else {
                        distance = total
                        isFinished = true
                    }
                }
            }

            Mode.TIMED -> {
                val durationSeconds = route.timedDurationMs / 1000.0
                elapsed += dt * multiplier
                if (durationSeconds > 0.0 && elapsed >= durationSeconds) {
                    if (loop) {
                        elapsed %= durationSeconds
                        wrapped = true
                    } else {
                        elapsed = durationSeconds
                        isFinished = true
                    }
                }
                distance = route.distanceAtTimeOffsetMs((elapsed * 1000.0).toLong())
            }
        }

        val position = route.positionAt(distance)
        val bearing = if (wrapped) {
            route.bearingAt(distance)
        } else {
            val moved = Geo.distanceMeters(lastPosition, position)
            if (moved > 0.3) Geo.bearingDegrees(lastPosition, position) else lastBearing
        }

        val delta = distance - before
        speedMps = if (delta >= 0.0 && dt > 0.0) (delta / dt).coerceIn(0.0, 30.0) else speedMps

        val sample = Sample(
            position = position,
            bearing = bearing,
            speedMps = speedMps,
            altitudeMeters = route.elevationAt(distance),
            distanceMeters = distance,
            elapsedSeconds = elapsed
        )

        lastPosition = position
        lastBearing = bearing
        return sample
    }
}
