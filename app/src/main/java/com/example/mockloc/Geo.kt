package com.example.mockloc

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 一个经纬度点。用 double，不用 osmdroid 的 GeoPoint，方便在 Service 里复用。 */
data class LatLng(val lat: Double, val lon: Double)

/** 模拟器输出的一帧。比 android.location.Location 轻，由 Service 翻译成 Location。 */
data class Sample(
    val position: LatLng,
    val bearing: Float,
    val speedMps: Double,
    val altitudeMeters: Double,
    val distanceMeters: Double,
    val elapsedSeconds: Double
)

object Geo {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** 两点间球面距离（米）。 */
    fun distanceMeters(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    /** a -> b 的方位角，0..360，正北为 0。 */
    fun bearingDegrees(a: LatLng, b: LatLng): Float {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    /** 线性插值。城市尺度下误差可忽略。 */
    fun interpolate(a: LatLng, b: LatLng, t: Double): LatLng =
        LatLng(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
}
