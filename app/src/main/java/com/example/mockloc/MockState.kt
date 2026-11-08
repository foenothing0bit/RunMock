package com.example.mockloc

import android.location.Location

/**
 * Service 与 Activity 之间共享的运行状态。
 *
 * Activity 在前台时轮询它刷新界面，避免 bindService 的样板代码；
 * [route] 也放在这里，因为一条 GPX 可能几百 KB，塞进 Intent 会撞 Binder 事务上限。
 */
object MockState {

    /** 待回放 / 正在回放的路线，由 Activity 写入。 */
    @Volatile
    var route: Route? = null

    @Volatile
    var running: Boolean = false

    @Volatile
    var paused: Boolean = false

    @Volatile
    var status: String = ""

    @Volatile
    var lastLocation: Location? = null

    @Volatile
    var distanceMeters: Double = 0.0

    @Volatile
    var totalMeters: Double = 0.0

    @Volatile
    var elapsedSeconds: Double = 0.0

    /** 当前瞬时配速（秒/公里），速度为 0 时为 0。 */
    @Volatile
    var currentPaceSecPerKm: Double = 0.0
}
