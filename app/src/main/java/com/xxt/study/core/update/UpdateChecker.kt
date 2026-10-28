package com.xxt.study.core.update

import android.content.Context
import com.xxt.study.core.net.VerifyGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 检查更新。
 *
 * 三条硬约束，都是踩过坑才有的：
 *
 * 1. **用独立的 OkHttpClient，不带学习通 Cookie**。
 *    更新检查打的是 GitHub / 镜像站，用主通道（[com.xxt.study.core.net.Http]）
 *    会把 mooc1 的 Cookie 发给第三方域名，也会被那条 300ms 串行节流拖住。
 * 2. **超时短**（连接 8 秒 / 读 10 秒）。镜像挂掉时是"连不上"，不是"慢"，
 *    等久了只会让启动的第一次检查卡半分钟。
 * 3. **风控冷却期不检查**。风控是按请求特征累积的，
 *    这时候再冒出一个新域名请求，只会让账号状态更糟。
 */
class UpdateChecker(private val context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("update_state", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    // ---------------- 版本信息 ----------------

    data class Local(val code: Int, val name: String)

    fun local(): Local = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
        Local(code, info.versionName ?: "")
    }.getOrDefault(Local(0, ""))

    // ---------------- 节流 ----------------

    /**
     * 自动检查该不该跑。
     *
     * 手动检查（用户点"检查更新"）永远不受这里限制，
     * 否则用户会以为按钮坏了。
     */
    fun shouldAutoCheck(now: Long = System.currentTimeMillis()): Boolean {
        if (VerifyGate.cooldownText() != null) return false
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        return now - last >= AUTO_INTERVAL_MS
    }

    /** 用户点了"关闭"的版本：同版本不再自动弹，但手动检查仍然会告诉他 */
    fun dismissedVersion(): Int = prefs.getInt(KEY_DISMISSED, 0)

    fun dismiss(versionCode: Int) {
        prefs.edit().putInt(KEY_DISMISSED, versionCode).apply()
    }

    private fun markChecked(now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
    }

    // ---------------- 检查 ----------------

    sealed class Result {
        /** 有新版本 */
        data class Found(val available: UpdateAvailable) : Result()

        /** 已经是最新（或用户关掉过这个版本） */
        object UpToDate : Result()

        /** 所有源都失败 */
        data class Failed(val reason: String) : Result()

        /**
         * 被节流 / 风控冷却跳过，界面上不用说什么。
         * [reason] 非空时用来解释"为什么没查"（手动检查会给用户看）。
         */
        data class Skipped(val reason: String = "") : Result()
    }

    /**
     * @param force 手动检查：忽略 24 小时节流与"已关闭该版本"，
     *              但**仍然遵守风控冷却**——冷却期内连学习通都不碰，
     *              没必要为了一个更新检查去外网绕一圈，也没必要让用户以为"点了没反应"。
     */
    suspend fun check(force: Boolean = false): Result = withContext(Dispatchers.IO) {
        val me = local()
        if (me.code <= 0) return@withContext Result.Failed("读不到本机版本号")

        // cooldownText 本身已经是一句完整的人话，别在外面再包一层
        VerifyGate.cooldownText()?.let { return@withContext Result.Skipped(it) }

        if (!force && !shouldAutoCheck()) return@withContext Result.Skipped()

        var lastError = "所有更新源都连不上"

        for (route in UpdateSource.manifestRoutes()) {
            val body = fetch(route.url)
            if (body == null) {
                lastError = "${route.name} 连不上"
                continue
            }
            val manifest = UpdateManifest.parse(body)
            if (manifest == null) {
                // 镜像有时会返回自己的 HTML 错误页/限流页，内容是 200 但不是 JSON
                lastError = "${route.name} 返回的不是有效清单"
                continue
            }

            markChecked()

            if (manifest.versionCode <= me.code) return@withContext Result.UpToDate
            if (!force && manifest.versionCode <= dismissedVersion()) return@withContext Result.UpToDate

            return@withContext Result.Found(
                UpdateAvailable(
                    manifest = manifest,
                    source = route.name,
                    currentVersionCode = me.code,
                    currentVersionName = me.name,
                )
            )
        }

        // 失败也记账：否则镜像全挂时每次冷启动都要把 4 个源挨个超时一遍
        markChecked()
        Result.Failed(lastError)
    }

    private fun fetch(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            // GitHub raw 要带 UA，个别镜像会挡默认的 okhttp UA
            .header("User-Agent", "study-assist-updater")
            .header("Accept", "application/json,*/*")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        }
    }.getOrNull()

    private companion object {
        /** 24 小时：更新不是秒级的事，一天一次足够，也不会打扰用户 */
        const val AUTO_INTERVAL_MS = 24 * 60 * 60 * 1000L
        const val KEY_LAST_CHECK = "last_check_at"
        const val KEY_DISMISSED = "dismissed_version_code"
    }
}
