package com.example.mockloc

/** GPX 里的一个轨迹点。高程与时间戳都可能缺失。 */
data class RoutePoint(
    val lat: Double,
    val lon: Double,
    val elevation: Double? = null,
    val timeMs: Long? = null
) {
    val latLng: LatLng get() = LatLng(lat, lon)
}

/**
 * 一条可回放的轨迹。
 *
 * 构造时一次性算好累计距离、高程剖面与时间轴；回放时只做二分查找 + 插值，
 * 因此 1Hz 触发的开销可以忽略，几千个点的 GPX 也不会掉帧。
 */
class Route(val name: String, points: List<RoutePoint>) {

    val points: List<RoutePoint> = points.toList()

    /** 每个点相对起点的累计距离（米），长度与 [points] 相同。 */
    val cumulativeMeters: DoubleArray

    val totalMeters: Double
        get() = if (points.isEmpty()) 0.0 else cumulativeMeters.last()

    /** 所有点是否都带时间戳（可按 GPX 原始时间轴回放）。 */
    val hasTiming: Boolean

    /** 原始时间轴总时长（毫秒），无时间戳时为 0。 */
    val timedDurationMs: Long

    /** 每个点相对起点的时间偏移（毫秒），无时间戳时为 null。 */
    val timeOffsetsMs: LongArray?

    val hasElevation: Boolean

    private val elevationProfile: DoubleArray

    init {
        require(this.points.isNotEmpty()) { "轨迹点不能为空" }

        cumulativeMeters = DoubleArray(this.points.size)
        for (i in 1 until this.points.size) {
            cumulativeMeters[i] = cumulativeMeters[i - 1] +
                Geo.distanceMeters(this.points[i - 1].latLng, this.points[i].latLng)
        }

        val times = this.points.map { it.timeMs }
        val allTimed = times.isNotEmpty() && times.all { it != null }
        hasTiming = allTimed && this.points.size >= 2
        if (hasTiming) {
            val first = requireNotNull(times.first())
            val offsets = LongArray(times.size) { index -> requireNotNull(times[index]) - first }
            timeOffsetsMs = offsets
            timedDurationMs = offsets.last().coerceAtLeast(0L)
        } else {
            timeOffsetsMs = null
            timedDurationMs = 0L
        }

        hasElevation = this.points.any { it.elevation != null }
        elevationProfile = buildElevationProfile()
    }

    /** 距离 [distanceMeters] 处的坐标。 */
    fun positionAt(distanceMeters: Double): LatLng {
        if (points.size == 1) return points[0].latLng
        val clamped = distanceMeters.coerceIn(0.0, totalMeters)
        val i = segmentIndexForDistance(clamped)
        val segStart = cumulativeMeters[i - 1]
        val segLength = cumulativeMeters[i] - segStart
        val t = if (segLength <= 0.0) 0.0 else ((clamped - segStart) / segLength).coerceIn(0.0, 1.0)
        return Geo.interpolate(points[i - 1].latLng, points[i].latLng, t)
    }

    /** 距离 [distanceMeters] 处所在线段的方位角。 */
    fun bearingAt(distanceMeters: Double): Float {
        if (points.size < 2) return 0f
        val clamped = distanceMeters.coerceIn(0.0, totalMeters)
        val i = segmentIndexForDistance(clamped)
        return Geo.bearingDegrees(points[i - 1].latLng, points[i].latLng)
    }

    /** 距离 [distanceMeters] 处的高程（米）。没有高程数据时返回 0。 */
    fun elevationAt(distanceMeters: Double): Double {
        if (points.size == 1) return elevationProfile[0]
        val clamped = distanceMeters.coerceIn(0.0, totalMeters)
        val i = segmentIndexForDistance(clamped)
        val segStart = cumulativeMeters[i - 1]
        val segLength = cumulativeMeters[i] - segStart
        val t = if (segLength <= 0.0) 0.0 else ((clamped - segStart) / segLength).coerceIn(0.0, 1.0)
        return elevationProfile[i - 1] + (elevationProfile[i] - elevationProfile[i - 1]) * t
    }

    /**
     * 按原始时间轴取距离：偏移 [offsetMs] 毫秒时应该跑到哪里。
     * 没有时间戳时返回 0。
     */
    fun distanceAtTimeOffsetMs(offsetMs: Long): Double {
        val offsets = timeOffsetsMs ?: return 0.0
        if (offsets.size < 2) return 0.0
        val clamped = offsetMs.coerceIn(0L, offsets.last())
        var lo = 0
        var hi = offsets.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (offsets[mid] <= clamped) lo = mid else hi = mid - 1
        }
        if (lo >= offsets.size - 1) return totalMeters
        val dt = offsets[lo + 1] - offsets[lo]
        val t = if (dt <= 0L) 0.0 else ((clamped - offsets[lo]).toDouble() / dt).coerceIn(0.0, 1.0)
        return cumulativeMeters[lo] + (cumulativeMeters[lo + 1] - cumulativeMeters[lo]) * t
    }

    /** 累计爬升（米）。忽略小于 0.5 米的高程抖动。 */
    fun elevationGainMeters(): Double {
        if (points.size < 2) return 0.0
        var gain = 0.0
        var previous = elevationProfile[0]
        for (i in 1 until points.size) {
            val current = elevationProfile[i]
            val delta = current - previous
            if (delta > 0.5) gain += delta
            previous = current
        }
        return gain
    }

    // ---------------------------------------------------------------- 内部实现

    /** 返回距离 d 所在线段的终点下标，范围 1..points.size-1。 */
    private fun segmentIndexForDistance(distanceMeters: Double): Int {
        var lo = 1
        var hi = points.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cumulativeMeters[mid] < distanceMeters) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * 高程剖面：缺失的点用相邻已知值补齐，避免插值时出现 NaN。
     * 先用后向填充处理开头缺失，再前向填充。
     */
    private fun buildElevationProfile(): DoubleArray {
        val n = points.size
        val profile = DoubleArray(n)
        var last: Double? = null
        for (i in 0 until n) {
            val e = points[i].elevation
            if (e != null) last = e
            profile[i] = last ?: Double.NaN
        }
        var next: Double? = null
        for (i in n - 1 downTo 0) {
            if (!profile[i].isNaN()) next = profile[i]
            if (profile[i].isNaN()) profile[i] = next ?: 0.0
        }
        return profile
    }
}
