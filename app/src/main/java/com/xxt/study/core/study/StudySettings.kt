package com.xxt.study.core.study

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 主题模式。
 *
 * 三种而不是两种：很多人希望"白天浅色、晚上深色"跟着系统走，
 * 但也有人就是想固定深色（比如 OLED 省电），所以要留"跟随系统"这一档。
 */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    /** 落到"当前到底该用深色吗" */
    fun resolveDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

/**
 * 刷课偏好。
 *
 * 用普通 SharedPreferences 而不是 SecretStore：这几个值不敏感，
 * 而且要能被设置页同步读取。密钥那类才走加密存储。
 */
class StudySettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("study_prefs", Context.MODE_PRIVATE)

    /** 章节测验是否自动作答并提交 */
    var autoQuiz: Boolean
        get() = prefs.getBoolean(KEY_AUTO_QUIZ, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_QUIZ, value).apply()

    /** 只跑一遍课程还是跑完所有课程（当前 UI 只暴露单课程，留作扩展） */
    var stopOnError: Boolean
        get() = prefs.getBoolean(KEY_STOP_ON_ERROR, false)
        set(value) = prefs.edit().putBoolean(KEY_STOP_ON_ERROR, value).apply()

    /*
     * 主题要能被界面**立即观察到**：切一下就得整屏变色，不能等下次重启。
     * 所以除了落盘，还维护一份 StateFlow。
     */
    private val _theme = MutableStateFlow(readTheme())
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _theme.value = mode
    }

    private fun readTheme(): ThemeMode = runCatching {
        ThemeMode.valueOf(
            prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name,
        )
    }.getOrDefault(ThemeMode.SYSTEM)

    private companion object {
        const val KEY_AUTO_QUIZ = "auto_quiz"
        const val KEY_STOP_ON_ERROR = "stop_on_error"
        const val KEY_THEME = "theme_mode"
    }
}
