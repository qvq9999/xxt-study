package com.xxt.study.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 色板与主题。
 * 全项目只在这里出现色值，页面里一律引 [Palette]，不写死十六进制。
 *
 * 主色墨蓝配砖红：砖红是唯一高饱和色，只用于"未完成数量"和错误态，
 * 这样首页那一屏只有一个视觉焦点。
 */
object Palette {
    val ink = Color(0xFF1B1F24)
    val sub = Color(0xFF5C6470)
    val weak = Color(0xFF8C949F)
    val bg = Color(0xFFF5F6F8)
    val card = Color(0xFFFFFFFF)
    val line = Color(0xFFE6E8EB)

    val brand = Color(0xFF2A5C8A)
    val brandSoft = Color(0xFFE8EFF6)
    val alarm = Color(0xFFC0432F)
    val alarmSoft = Color(0xFFFBEAE7)
    val ok = Color(0xFF2F6D4F)
    val okSoft = Color(0xFFE8F2EC)
}

private val LightColors = lightColorScheme(
    primary = Palette.brand,
    onPrimary = Color.White,
    primaryContainer = Palette.brandSoft,
    onPrimaryContainer = Palette.brand,
    background = Palette.bg,
    onBackground = Palette.ink,
    surface = Palette.card,
    onSurface = Palette.ink,
    surfaceVariant = Palette.bg,
    onSurfaceVariant = Palette.sub,
    outline = Palette.line,
    error = Palette.alarm,
    onError = Color.White,
)

@Composable
fun StudyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}
