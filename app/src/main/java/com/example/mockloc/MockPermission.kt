package com.example.mockloc

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * 「模拟位置信息应用」的授权状态。
 *
 * Android 从 6.0 开始，普通 App 必须被用户手动在
 * 开发者选项 -> 选择模拟位置信息应用 里选中，否则 addTestProvider 会抛 SecurityException。
 * 这个选择没有 API 可以代劳，只能跳设置页让用户点。
 */
object MockPermission {
    private const val OP_STR = AppOpsManager.OPSTR_MOCK_LOCATION

    fun isMockApp(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val uid = Process.myUid()
        val pkg = context.packageName
        val mode = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(OP_STR, uid, pkg)
            } else {
                appOps.checkOpNoThrow(OP_STR, uid, pkg)
            }
        } catch (e: Exception) {
            return false
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 打开系统设置。优先直达开发者选项，失败则回退到应用详情页。 */
    fun openSettings(context: Context) {
        val candidates = listOf(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                // 试下一个
            }
        }
    }
}
