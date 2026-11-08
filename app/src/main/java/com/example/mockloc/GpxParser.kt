package com.example.mockloc

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** GPX 解析失败时抛出，message 直接展示给用户。 */
class GpxException(message: String) : Exception(message)

/**
 * 极简 GPX 解析器：只取轨点（trkpt / rtept / wpt）的经纬度、高程和时间。
 *
 * 不用第三方库，避免为了一个 XML 解析多引一个依赖；
 * 也不做 schema 校验——测试用轨迹文件通常来自各家运动 App 的导出，容错比严格更重要。
 */
object GpxParser {

    private const val MAX_POINTS = 100_000

    fun parse(input: InputStream): Route {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val points = ArrayList<RoutePoint>(512)
        var routeName: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                // 未开启命名空间处理，带前缀的写法（如 gpx:trkpt）会保留前缀，先剥掉
                when (parser.name?.substringAfter(':')) {
                    "name" -> {
                        if (routeName == null) routeName = readText(parser)
                    }

                    "trkpt", "rtept", "wpt" -> {
                        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        if (lat != null && lon != null &&
                            lat in -90.0..90.0 && lon in -180.0..180.0
                        ) {
                            points.add(readPoint(parser, lat, lon))
                            if (points.size > MAX_POINTS) {
                                throw GpxException("轨迹点过多（超过 $MAX_POINTS 个），请先抽稀")
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }

        if (points.size < 2) {
            throw GpxException("GPX 中没有找到有效轨迹点（至少需要 2 个 trkpt）")
        }

        val cleaned = dropConsecutiveDuplicates(points)
        if (cleaned.size < 2) {
            throw GpxException("轨迹点全部重合，无法模拟移动")
        }

        val name = routeName?.trim()?.takeIf { it.isNotEmpty() } ?: "未命名轨迹"
        return Route(name, cleaned)
    }

    /** 读取 trkpt 的子节点（ele / time），并把解析器推进到该点的结束标签。 */
    private fun readPoint(parser: XmlPullParser, lat: Double, lon: Double): RoutePoint {
        var elevation: Double? = null
        var timeMs: Long? = null

        val pointDepth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == pointDepth)) {
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.START_TAG) {
                when (parser.name?.substringAfter(':')) {
                    "ele" -> elevation = readText(parser)?.toDoubleOrNull()
                    "time" -> timeMs = parseTime(readText(parser))
                }
            }
            event = parser.next()
        }
        return RoutePoint(lat, lon, elevation, timeMs)
    }

    /** 读当前标签的文本；nextText 会顺带推进到结束标签。 */
    private fun readText(parser: XmlPullParser): String? =
        try {
            parser.nextText()?.trim()
        } catch (e: Exception) {
            null
        }

    /** 支持 "2024-05-01T08:00:00Z"、带时区偏移，以及不带时区的本地时间。 */
    private fun parseTime(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (e: Exception) {
            try {
                LocalDateTime.parse(text)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun dropConsecutiveDuplicates(points: List<RoutePoint>): List<RoutePoint> {
        val out = ArrayList<RoutePoint>(points.size)
        for (point in points) {
            val last = out.lastOrNull()
            if (last == null || last.lat != point.lat || last.lon != point.lon) {
                out.add(point)
            }
        }
        return out
    }
}
