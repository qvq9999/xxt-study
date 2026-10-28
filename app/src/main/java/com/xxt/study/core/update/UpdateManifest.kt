package com.xxt.study.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 更新清单（`latest.json`）。
 *
 * 放在仓库里用 raw 访问（`update/latest.json`），而不是每次发版当 release asset 传：
 * 发版只要改这一个文件 + 传 APK，少一步出错的地方；镜像也能直接缓存 raw 地址。
 *
 * 字段全部有默认值——清单手写、机器拼都可能漏，漏了不该整次检查失败。
 */
@Serializable
data class UpdateManifest(
    /** 必须严格递增，比较只看它，不看 versionName 字符串 */
    @SerialName("versionCode") val versionCode: Int = 0,
    @SerialName("versionName") val versionName: String = "",
    @SerialName("sizeBytes") val sizeBytes: Long = 0,
    /** APK 的 sha256，小写十六进制。校验不过一律不装 */
    @SerialName("sha256") val sha256: String = "",
    /** 更新日志，**一律简写**：一条一句，最多三条 */
    @SerialName("notes") val notes: List<String> = emptyList(),
    /** 低于这个版本必须先升——目前不用，留给"旧版接口作废"的场景 */
    @SerialName("minVersionCode") val minVersionCode: Int = 0,
    @SerialName("packageName") val packageName: String = "",
    /**
     * APK 签名证书的 sha256。
     *
     * **目前只在清单里保留字段，客户端不强制校验**：
     * 覆盖安装时系统安装器自己就会拒掉签名不一致的包，
     * 再算一遍只是重复同一道检查，却要多解析一次 APK 的签名块。
     * 真要防"被换包"，sha256 已经挡住了。
     */
    @SerialName("signatureSha256") val signatureSha256: String = "",
    /** GitHub Release 的 APK 直链。镜像地址在客户端拼 */
    @SerialName("url") val url: String = "",
) {

    /**
     * 能不能拿它去更新。
     *
     * 判得很松（只挡明显坏掉的清单），但**包名和 sha256 有一处不对就不装**：
     * 装错包比不更新严重得多。
     */
    fun valid(currentPackage: String = "com.xxt.study"): Boolean {
        if (versionCode <= 0) return false
        if (versionName.isBlank()) return false
        if (url.isBlank() || !url.startsWith("http")) return false
        if (packageName.isNotBlank() && packageName != currentPackage) return false
        return true
    }

    /**
     * 给弹窗用的更新日志。
     *
     * 用户要求"日志全部简写"，所以这里硬性收口：
     * 最多 [maxLines] 条，每条最多 [maxChars] 字，超了截断加省略号。
     * 这样即使清单里写了一整段，弹窗也不会被撑成一屏。
     */
    fun shortNotes(maxLines: Int = 3, maxChars: Int = 36): List<String> =
        notes.asSequence()
            .map { it.replace(Regex("\\s+"), " ").trim().removePrefix("·").trim() }
            .filter { it.isNotEmpty() }
            .take(maxLines)
            .map { if (it.length <= maxChars) it else it.take(maxChars - 1) + "…" }
            .toList()

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * 解析清单。
         *
         * 返回 null 的情况：不是 JSON（镜像挂了会返回 HTML 错误页）、
         * 或者缺关键字段。调用方据此换下一个源。
         */
        fun parse(text: String): UpdateManifest? {
            val raw = text.trim()
            if (!raw.startsWith("{")) return null
            val m = runCatching { json.decodeFromString<UpdateManifest>(raw) }.getOrNull() ?: return null
            return m.takeIf { it.valid() }
        }
    }
}

/** 检查结果：要更新时带上"从哪个源拿到的"和"当前版本" */
data class UpdateAvailable(
    val manifest: UpdateManifest,
    /** 命中的镜像域名，空串表示直连 GitHub */
    val source: String,
    val currentVersionCode: Int,
    val currentVersionName: String,
    /**
     * 下载完成后的本地安装包。
     *
     * 放在这里而不是单独存一份，是因为"下载好了"这件事必须跟着 available 一起走：
     * 用户关掉弹窗再打开、或者点通知回来，都要能直接拿到文件去装。
     */
    val file: java.io.File? = null,
) {
    val sizeText: String get() = formatBytes(manifest.sizeBytes)

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes <= 0 -> "未知大小"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            // 必须指定 Locale.US：默认 Locale 在有些系统上会把小数点写成逗号（2,47 MB）
            else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        }
    }
}
