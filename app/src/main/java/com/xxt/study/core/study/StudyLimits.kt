package com.xxt.study.core.study

/**
 * 刷课速率上限。
 *
 * 学习通视频播放器自带的倍速档位最高是 **2.0×**（0.75 / 1.0 / 1.25 / 1.5 / 2.0）。
 * 之前这里允许用户自己填 1~6，本质是「每 60 秒上报 3~6 分钟进度」——
 * 那已经不是在模拟 2 倍速播放，而是编一个平台根本不存在的速度，异常风险很高。
 *
 * 现在统一改成：**只按平台允许的最高倍速走**，用户不可调。
 * tick 的真实含义回到「这 60 秒里视频应该前进了多少秒」。
 */
object StudyLimits {

    /** 平台允许的最高倍速 */
    const val PLATFORM_MAX_RATE = 2.0

    /** 一轮上报的真实间隔（秒）。服务端 reportTimeInterval 一般 58~60 秒 */
    const val TICK_SECONDS = 60.0

    /** 展示给用户看的说法，设置页与使用说明共用，避免两处文案不一致 */
    val rateLabel: String
        get() = "×%.1f（平台最高倍速）".format(PLATFORM_MAX_RATE)
}
