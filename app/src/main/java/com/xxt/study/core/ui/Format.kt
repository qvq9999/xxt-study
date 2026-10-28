package com.xxt.study.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/** 把时间戳说成人话：刚刚 / 3 分钟前 / 2 小时前 / 具体日期 */
fun agoText(at: Long, now: Long = System.currentTimeMillis()): String {
    if (at <= 0) return "尚未刷新"
    val d = now - at
    return when {
        d < TimeUnit.MINUTES.toMillis(1) -> "刚刚"
        d < TimeUnit.HOURS.toMillis(1) -> "${d / TimeUnit.MINUTES.toMillis(1)} 分钟前"
        d < TimeUnit.DAYS.toMillis(1) -> "${d / TimeUnit.HOURS.toMillis(1)} 小时前"
        else -> "${d / TimeUnit.DAYS.toMillis(1)} 天前"
    }
}

/**
 * 截止倒计时文案。
 *
 * 时间粒度按"还剩多久"自动切换：几小时就说到小时，几天就说到天。
 * 不算到秒——作业截止精确到分钟没意义，而且每秒刷新会白白唤醒 UI。
 */
fun countdownText(deadline: Long, now: Long): String {
    val left = deadline - now
    return when {
        left <= 0 -> "已过期"
        left < TimeUnit.HOURS.toMillis(1) -> "剩 ${(left / TimeUnit.MINUTES.toMillis(1)).coerceAtLeast(1)} 分钟"
        left < TimeUnit.DAYS.toMillis(1) -> "剩 ${left / TimeUnit.HOURS.toMillis(1)} 小时"
        else -> {
            val d = left / TimeUnit.DAYS.toMillis(1)
            val h = (left % TimeUnit.DAYS.toMillis(1)) / TimeUnit.HOURS.toMillis(1)
            "剩 $d 天 $h 小时"
        }
    }
}

/** 24 小时内截止算紧急，UI 用告警色 */
fun isUrgent(deadline: Long, now: Long): Boolean {
    val left = deadline - now
    return left in 1..TimeUnit.DAYS.toMillis(1)
}

/** 已经过期 */
fun isExpired(deadline: Long, now: Long): Boolean = now >= deadline

/**
 * 每 [intervalMs] 推一次当前时间。
 *
 * 倒计时和"红点会不会消失"都依赖它：跨过截止时间那一刻，
 * 界面上不需要做任何特殊处理，下一拍自己就变了。
 * 30 秒足够——文案精确到分钟，秒级刷新只是白耗电。
 */
@Composable
fun rememberNow(intervalMs: Long = 30_000L): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(intervalMs)
        }
    }
    return now
}
