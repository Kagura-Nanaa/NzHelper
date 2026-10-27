package me.neko.nzhelper.core.datastore

import android.content.Context
import androidx.core.content.edit

object TimerSettings {
    private const val PREFS_NAME = "settings_prefs"
    private const val KEY_FLOATING_ENABLED = "floating_timer_enabled"
    private const val KEY_POS_PORTRAIT = "floating_timer_pos_portrait"
    private const val KEY_POS_LANDSCAPE = "floating_timer_pos_landscape"

    enum class Side { LEFT, RIGHT }

    /** 一次完整的摆放结果：贴边侧 + 窗口顶边相对屏幕顶部的像素 */
    data class FloatingPlacement(val side: Side, val y: Int)

    fun isFloatingEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_FLOATING_ENABLED, false)
    }

    fun setFloatingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_FLOATING_ENABLED, enabled) }
    }

    fun getFloatingPlacement(context: Context, landscape: Boolean): FloatingPlacement? {
        val raw = prefs(context).getString(placementKey(landscape), null) ?: return null
        val separator = raw.lastIndexOf(':')
        if (separator <= 0) return null
        val side = runCatching { Side.valueOf(raw.substring(0, separator)) }.getOrNull() ?: return null
        val y = raw.substring(separator + 1).toIntOrNull() ?: return null
        return FloatingPlacement(side, y)
    }

    fun setFloatingPlacement(context: Context, landscape: Boolean, placement: FloatingPlacement) {
        prefs(context).edit {
            putString(placementKey(landscape), "${placement.side.name}:${placement.y}")
        }
    }

    private fun placementKey(landscape: Boolean): String =
        if (landscape) KEY_POS_LANDSCAPE else KEY_POS_PORTRAIT

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
