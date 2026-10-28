package com.xxt.study.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 下载安装包并交给系统安装器。
 *
 * 为什么不用 DownloadManager：
 * 1. **没法换源**——镜像挂了只能整次失败，而这里要按 [UpdateSource] 的顺序挨个试；
 * 2. **没法流式校验**——DownloadManager 只告诉你"下完了"，
 *    想比对 sha256 还得再把整个文件读一遍；
 * 3. 部分 ROM 上 DownloadManager 的可见通知会被拦，进度反而看不见。
 *
 * 自己下就三件事：换源、边下边算 sha256、下完丢给 FileProvider。
 * 包只有 2 MB 出头，进度条在弹窗里显示就够了。
 */
class UpdateInstaller(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    sealed class Outcome {
        data class Ok(val file: File, val source: String) : Outcome()
        data class Fail(val message: String) : Outcome()
    }

    /** 下载目录：应用专属外部目录，卸载时随应用一起清掉，不需要存储权限 */
    private val dir: File
        get() = File(context.getExternalFilesDir(null), "update").apply { if (!exists()) mkdirs() }

    // ---------------- 权限 ----------------

    /** Android 8 起装 APK 要用户显式授权"安装未知应用" */
    fun canInstall(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
    } else {
        true
    }

    /** 打开"允许安装未知应用"的系统页面，让用户自己开——我们没法代开 */
    fun permissionIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    // ---------------- 下载 ----------------

    /**
     * 下载并校验。
     *
     * 校验不过**不报错终止**，而是换下一个源——
     * 镜像站缓存了旧文件是常见情况，重下一个源通常就好了。
     * 所有源都失败才算失败。
     *
     * @param onProgress 0..100；拿不到文件大小时不会回调
     */
    suspend fun download(
        available: UpdateAvailable,
        onProgress: (Int) -> Unit = {},
    ): Outcome = withContext(Dispatchers.IO) {
        val manifest = available.manifest
        val routes = UpdateSource.downloadRoutes(manifest.url)
        if (routes.isEmpty()) return@withContext Outcome.Fail("清单里没有下载地址")

        var lastError = "下载失败"
        val target = File(dir, "study-${manifest.versionCode}.apk")

        for (route in routes) {
            val part = File(dir, "study-${manifest.versionCode}.apk.part")
            runCatching { if (part.exists()) part.delete() }

            val digest = try {
                fetchToFile(route.url, part, onProgress)
            } catch (e: Exception) {
                lastError = "${route.name}：${e.message ?: "下载中断"}"
                runCatching { part.delete() }
                continue
            }

            // sizeBytes 是清单里写的预期值；对不上说明镜像给了旧包或者被截断
            if (manifest.sizeBytes > 0 && part.length() != manifest.sizeBytes) {
                lastError = "${route.name}：大小不符（${part.length()} ≠ ${manifest.sizeBytes}）"
                runCatching { part.delete() }
                continue
            }

            if (manifest.sha256.isNotBlank() && !digest.equals(manifest.sha256, ignoreCase = true)) {
                lastError = "${route.name}：校验失败，可能缓存了旧包"
                runCatching { part.delete() }
                continue
            }

            val renamed = runCatching {
                if (target.exists()) target.delete()
                part.renameTo(target)
            }.getOrDefault(false)

            if (!renamed) {
                lastError = "${route.name}：写入安装包失败"
                runCatching { part.delete() }
                continue
            }

            onProgress(100)
            return@withContext Outcome.Ok(target, route.name)
        }

        Outcome.Fail(lastError)
    }

    /**
     * 下载到 [target]，同时算出 sha256。
     *
     * 边下边算，不再把 2 MB 读第二遍；缓冲区 64 KB，比逐字节读快一个量级。
     *
     * @return 小写十六进制 sha256
     */
    private fun fetchToFile(url: String, target: File, onProgress: (Int) -> Unit): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "study-assist-updater")
            .get()
            .build()

        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("响应为空")
            val total = body.contentLength()
            val sha = MessageDigest.getInstance("SHA-256")

            body.byteStream().use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        sha.update(buf, 0, n)
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            // 只在百分比变化时回调，避免几十次无意义的组合重组
                            val pct = ((read * 100) / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                    out.flush()
                }
            }
            sha.digest().toHex()
        }
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    // ---------------- 安装 ----------------

    /**
     * 交给系统安装器。
     *
     * FileProvider 的 authority 必须是 `${applicationId}.fileprovider`，
     * 和 AndroidManifest 里的注册一字不差——debug 版会多一个 `.debug` 后缀，
     * 所以这里用 `context.packageName` 拼而不是写死。
     */
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 装完之后清掉安装包；也顺手清上一次留下的残片 */
    fun cleanup(keep: File? = null) {
        runCatching {
            dir.listFiles()?.forEach { f ->
                if (f != keep) runCatching { f.delete() }
            }
        }
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        val HEX = "0123456789abcdef".toCharArray()
    }
}
