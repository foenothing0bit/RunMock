package com.example.mockloc

import java.util.Locale

/** 界面与通知里复用的格式化。 */
object Fmt {

    /** 330 -> "5'30\"" */
    fun paceSecondsToText(secondsPerKm: Double): String {
        if (secondsPerKm <= 0.0 || !secondsPerKm.isFinite()) return "--'--\""
        val total = secondsPerKm.toInt().coerceAtLeast(0)
        return String.format(Locale.US, "%d'%02d\"", total / 60, total % 60)
    }

    /** 1234.5 -> "1.23 km" */
    fun distanceKm(meters: Double): String =
        String.format(Locale.US, "%.2f km", meters / 1000.0)

    /** 3725 -> "1:02:05"，185 -> "3:05" */
    fun duration(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, secs)
        }
    }
}
