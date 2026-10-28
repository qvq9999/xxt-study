package com.xxt.study.core.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 一套配色。
 *
 * 为什么要有 [LightPalette] / [DarkPalette] 两份而不是写死一套：
 * 深浅模式切换要求同一处 `Palette.xxx` 在运行时给出不同值，
 * 靠 CompositionLocal 在主题层替换，业务代码一个字都不用改。
 */
data class PaletteColors(
    val ink: Color,
    val sub: Color,
    val weak: Color,
    val bg: Color,
    val card: Color,
    val line: Color,
    val brand: Color,
    val brandSoft: Color,
    val alarm: Color,
    val alarmSoft: Color,
    val ok: Color,
    val okSoft: Color,
)

/** 浅色：墨蓝配砖红，砖红是唯一高饱和色，只用于"未完成数量"和错误态 */
val LightPalette = PaletteColors(
    ink = Color(0xFF1B1F24),
    sub = Color(0xFF5C6470),
    weak = Color(0xFF8C949F),
    bg = Color(0xFFF5F6F8),
    card = Color(0xFFFFFFFF),
    line = Color(0xFFE6E8EB),
    brand = Color(0xFF2A5C8A),
    brandSoft = Color(0xFFE8EFF6),
    alarm = Color(0xFFC0432F),
    alarmSoft = Color(0xFFFBEAE7),
    ok = Color(0xFF2F6D4F),
    okSoft = Color(0xFFE8F2EC),
)

/**
 * 深色：背景是**纯黑 #000000**。
 *
 * OLED 上一个显示 #000000 的像素是**完全熄灭**的——不发光、不耗电、不偏色，
 * 这才是"黑色部分完全不显示颜色"。任何 #0A0A0A 之类的"近黑"仍然亮着，
 * 关灯能看出发灰。
 *
 * 代价是纯黑没有"地板"：Material 的层次靠"越高的面越亮"表达，
 * 从纯黑起跳时卡片会和背景糊成一片。所以：
 * - [card] 用 #0D0D0D（约 5% 亮度）：OLED 上几乎看不出，但足以区分层次；
 * - 文字用 #E8EAED 而不是纯白：纯白压在纯黑上会起光晕（halation），久看刺眼；
 * - [brand] / [alarm] / [ok] 都比浅色版**调亮**：原来那几个深色在纯黑上读不出来。
 */
val DarkPalette = PaletteColors(
    ink = Color(0xFFE8EAED),
    sub = Color(0xFFA3A9B3),
    weak = Color(0xFF6E757F),
    bg = Color(0xFF000000),
    card = Color(0xFF0D0D0D),
    line = Color(0xFF232629),
    brand = Color(0xFF7FB2E5),
    brandSoft = Color(0xFF12202E),
    alarm = Color(0xFFF08A78),
    alarmSoft = Color(0xFF2A1613),
    ok = Color(0xFF6FBF8F),
    okSoft = Color(0xFF10241A),
)

/** 当前生效的配色；[StudyTheme] 在主题层替换它 */
internal val LocalPalette = staticCompositionLocalOf { LightPalette }

/**
 * 全项目只在这里出现色值，页面里一律引 [Palette]。
 *
 * 每个属性都是 `@Composable` getter——只能在上层是 Composable 的地方读。
 * 也就是说 **`Palette.xxx` 不能出现在普通函数、`remember {}`、常量初始化里**，
 * 那些地方请直接用 [LightPalette] / [DarkPalette] 取具体值。
 */
object Palette {
    val ink: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.ink

    val sub: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.sub

    val weak: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.weak

    val bg: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.bg

    val card: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.card

    val line: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.line

    val brand: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.brand

    val brandSoft: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.brandSoft

    val alarm: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.alarm

    val alarmSoft: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.alarmSoft

    val ok: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.ok

    val okSoft: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.okSoft
}

private val LightColors = lightColorScheme(
    primary = LightPalette.brand,
    onPrimary = Color.White,
    primaryContainer = LightPalette.brandSoft,
    onPrimaryContainer = LightPalette.brand,
    background = LightPalette.bg,
    onBackground = LightPalette.ink,
    surface = LightPalette.card,
    onSurface = LightPalette.ink,
    surfaceVariant = LightPalette.bg,
    onSurfaceVariant = LightPalette.sub,
    outline = LightPalette.line,
    error = LightPalette.alarm,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = DarkPalette.brand,
    onPrimary = Color(0xFF06121C),
    primaryContainer = DarkPalette.brandSoft,
    onPrimaryContainer = DarkPalette.brand,
    background = DarkPalette.bg,
    onBackground = DarkPalette.ink,
    surface = DarkPalette.card,
    onSurface = DarkPalette.ink,
    surfaceVariant = DarkPalette.bg,
    onSurfaceVariant = DarkPalette.sub,
    outline = DarkPalette.line,
    error = DarkPalette.alarm,
    onError = Color(0xFF1A0A07),
)

/**
 * 主题。
 *
 * @param dark true = 深色（纯黑背景）。由 [com.xxt.study.core.study.ThemeMode] 决定，
 *             "跟随系统"时取 `isSystemInDarkTheme()`。
 */
@Composable
fun StudyTheme(
    dark: Boolean = false,
    content: @Composable () -> Unit,
) {
    /*
     * 系统栏图标的明暗，**只能在这里用运行时 API 设**。
     *
     * 为什么不能靠主题属性：`android:windowLightStatusBar` / `android:statusBarColor`
     * 从 **Android 15（API 35）起被系统完全忽略**（targetSdk 35+ 强制 edge-to-edge）。
     * 于是状态栏变透明、露出我们的背景，图标却仍是系统默认白色——
     * 白图标压在浅色背景上，时间和电量基本看不见。这就是"状态栏不反色"的真实原因：
     * 不是没设，而是设了系统不读。
     *
     * 为什么必须写在 Composable 里：放在 Activity.onCreate 设完，
     * 后续 Window/Insets 状态变化会把它覆盖回去；SideEffect 每次组合都重跑，
     * 等于持续重新声明一遍。
     *
     * 深色模式取反：纯黑背景 → 白色图标。
     */
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalPalette provides if (dark) DarkPalette else LightPalette) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
    }
}

/** Compose 的 view.context 可能是被包裹过的，往上找真正的 Activity */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
