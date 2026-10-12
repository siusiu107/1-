package io.github.siusiu107.shizukuinstaller.pkg

import android.content.Context

/** SharedPreferences 기반 간단 설정 + 최근 설치 기록. */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("ssuk", Context.MODE_PRIVATE)

    enum class DefaultEngine { AUTO, NORMAL, SHIZUKU }
    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    var defaultEngine: DefaultEngine
        get() = runCatching { DefaultEngine.valueOf(sp.getString(K_ENGINE, "AUTO")!!) }.getOrDefault(DefaultEngine.AUTO)
        set(v) = sp.edit().putString(K_ENGINE, v.name).apply()

    var theme: ThemeMode
        get() = runCatching { ThemeMode.valueOf(sp.getString(K_THEME, "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)
        set(v) = sp.edit().putString(K_THEME, v.name).apply()

    var grantPermissions: Boolean
        get() = sp.getBoolean(K_GRANT, true)
        set(v) = sp.edit().putBoolean(K_GRANT, v).apply()

    var allowDowngrade: Boolean
        get() = sp.getBoolean(K_DOWNGRADE, false)
        set(v) = sp.edit().putBoolean(K_DOWNGRADE, v).apply()

    /** 무선 디버깅 페어링에 한 번이라도 성공했는지 (백그라운드 재연결 시도 여부 판단) */
    var paired: Boolean
        get() = sp.getBoolean(K_PAIRED, false)
        set(v) = sp.edit().putBoolean(K_PAIRED, v).apply()

    data class HistoryItem(val pkg: String, val label: String, val time: Long)

    fun history(): List<HistoryItem> =
        sp.getString(K_HISTORY, "").orEmpty()
            .split("\n")
            .filter { it.isNotBlank() }
            .mapNotNull {
                val p = it.split("\t")
                if (p.size >= 3) HistoryItem(p[0], p[1], p[2].toLongOrNull() ?: 0L) else null
            }

    fun addHistory(pkg: String, label: String, time: Long) {
        val items = (listOf(HistoryItem(pkg, label, time)) + history().filter { it.pkg != pkg }).take(12)
        val serialized = items.joinToString("\n") { "${it.pkg}\t${it.label}\t${it.time}" }
        sp.edit().putString(K_HISTORY, serialized).apply()
    }

    fun clearHistory() = sp.edit().remove(K_HISTORY).apply()

    private companion object {
        const val K_ENGINE = "default_engine"
        const val K_THEME = "theme"
        const val K_GRANT = "grant_perms"
        const val K_DOWNGRADE = "allow_downgrade"
        const val K_HISTORY = "history"
        const val K_PAIRED = "adb_paired"
    }
}
