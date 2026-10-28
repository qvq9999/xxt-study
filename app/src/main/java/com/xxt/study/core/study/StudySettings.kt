package com.xxt.study.core.study

import android.content.Context

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

    private companion object {
        const val KEY_AUTO_QUIZ = "auto_quiz"
        const val KEY_STOP_ON_ERROR = "stop_on_error"
    }
}
