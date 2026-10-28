package com.xxt.study.data.remote.chaoxing

import java.security.MessageDigest
import java.util.concurrent.ThreadLocalRandom

/**
 * 视频进度上报的签名与 URL 拼装。
 *
 * 算法逐字对齐 SuperAutoStudy 的 CourseUtil.getEnc，salt 也是同一个常量——
 * 这是服务端定的，不是可配置项，改一个字符服务端就判签名无效。
 *
 * 为什么单独抽出来：这是整个刷课链路里最容易悄悄写错、又最难从现象反推的一环。
 * 纯函数 → 可以脱离网络直接单测，签名错了在单测里就能看出来，不用等真机 403。
 */
object VideoSign {

    /** Kotlin 里 `$p` 会被当插值，必须转义；字面值就是 Java 里的 d_yHJ!$pdA~5 */
    private const val SALT = "d_yHJ!\$pdA~5"

    /** 服务端验签用的固定串：`[clazzId][uid][jobId][objectId][pt*1000][salt][dur*1000][0_dur]` */
    fun enc(
        clazzId: String,
        uid: String,
        jobId: String,
        objectId: String,
        playingTime: Long,
        duration: Long,
    ): String {
        val raw = "[$clazzId][$uid][$jobId][$objectId][${playingTime * 1000}]" +
            "[$SALT][${duration * 1000}][0_$duration]"
        return md5Hex(raw)
    }

    fun md5Hex(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray())
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /**
     * 进度日志 URL。
     *
     * [otherInfo] 是卡片里下发的原样串，服务端按它定位任务点，**不要**做 URL 编码——
     * 原项目直接拼也跑得通，编码后反而对不上。
     * 若它自己没带 courseId，补一个，否则部分课程会缺参。
     */
    fun logUrl(
        cpi: String,
        dtoken: String,
        clazzId: String,
        courseId: String,
        uid: String,
        jobId: String,
        objectId: String,
        otherInfo: String,
        playingTime: Long,
        duration: Long,
        dtype: String,
        rt: String,
        timestamp: Long,
    ): String {
        val mid = if (otherInfo.contains("courseId")) {
            "otherInfo=$otherInfo&"
        } else {
            "otherInfo=$otherInfo&courseId=$courseId&"
        }
        val enc = enc(clazzId, uid, jobId, objectId, playingTime, duration)
        return "https://mooc1.chaoxing.com/mooc-ans/multimedia/log/a/$cpi/$dtoken" +
            "?clazzId=$clazzId" +
            "&playingTime=$playingTime" +
            "&duration=$duration" +
            "&clipTime=0_$duration" +
            "&objectId=$objectId" +
            "&$mid" +
            "jobid=$jobId" +
            "&userid=$uid" +
            "&isdrag=3" +
            "&view=pc" +
            "&enc=$enc" +
            "&rt=$rt" +
            "&dtype=$dtype" +
            "&_t=$timestamp"
    }

    /**
     * 一轮上报要声称看了多少秒。
     *
     * 原项目是 `random(100,120) * speed`，真实等待固定 60 秒。
     * 也就是说 speed 并不是播放器倍速，而是「每 60 秒真实时间上报多少秒进度」，
     * 3 倍速实际约等于 5~6 倍。这里保留同一公式，speed 由设置项控制。
     */
    /**
     * 一轮上报应该前进多少秒。
     *
     * 语义是「真实等待 TICK_SECONDS 秒 × 平台倍速」——2 倍速播放 60 秒，视频刚好前进 120 秒。
     * 原来的写法是 random(100,120) × speed，speed=3 时等于每 60 秒前进 5~6 分钟，
     * 那不是"倍速播放"，是凭空编进度。现在只允许平台内的速率。
     *
     * 保留 ±2% 的抖动：纯粹为了避免每轮都精确 120 秒这种机械特征。
     */
    fun tickSeconds(rate: Double, tickSeconds: Double = 60.0): Long {
        val jitter = ThreadLocalRandom.current().nextDouble(0.98, 1.02)
        return (tickSeconds * rate * jitter).toLong().coerceAtLeast(1L)
    }
}
