package com.xxt.study.core.ai

/**
 * 预设的 AI 服务商。
 *
 * 地址都是官方文档里的 OpenAI 兼容入口（2026-10 核对），不是猜的：
 * - MiMo：小米开放平台，OpenAI 兼容 `https://api.xiaomimimo.com/v1`
 * - Agnes：`api.agnes-ai.cn`（国内站，实测可用；apihub 那个连不上）
 *
 * 「扫描模型」走各家统一的 `GET {base}/models`，所以预设只要 Base URL 对就行；
 * 不支持该端点的服务商扫不出来时，用户可以手填模型名。
 */
data class AiProvider(
    val id: String,
    val label: String,
    val baseUrl: String,
    /** 默认模型名；留空表示"必须扫描或手填" */
    val defaultModel: String = "",
    val hint: String = "",
)

object AiProviders {

    val all: List<AiProvider> = listOf(
        AiProvider(
            id = "mimo",
            label = "MiMo（小米）",
            baseUrl = "https://api.xiaomimimo.com/v1",
            defaultModel = "mimo-v2.5-pro",
            hint = "小米开放平台，Key 形如 sk-xxx",
        ),
        AiProvider(
            id = "agnes-cn",
            label = "Agnes 国内站",
            // 用户实测可用地址（不是 apihub 那个）；apihub.agnes-ai.cn 会连不上
            baseUrl = "https://api.agnes-ai.cn/v1",
            defaultModel = "agnes-2.5-flash",
            hint = "国内直连，文本模型目前免费",
        ),
        AiProvider(
            id = "deepseek",
            label = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            defaultModel = "deepseek-chat",
            hint = "国内直连，便宜稳定",
        ),
        AiProvider(
            id = "qwen",
            label = "通义千问",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen-plus",
            hint = "阿里云百炼兼容模式",
        ),
        AiProvider(
            id = "kimi",
            label = "Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
            defaultModel = "moonshot-v1-8k",
            hint = "月之暗面",
        ),
        AiProvider(
            id = "zhipu",
            label = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            defaultModel = "glm-4-flash",
            hint = "glm-4-flash 有免费额度",
        ),
        AiProvider(
            id = "siliconflow",
            label = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            defaultModel = "Qwen/Qwen2.5-7B-Instruct",
            hint = "聚合站，模型多",
        ),
        AiProvider(
            id = "openai",
            label = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini",
            hint = "需要能直连",
        ),
        AiProvider(
            id = "custom",
            label = "自定义",
            baseUrl = "",
            defaultModel = "",
            hint = "自己填 OpenAI 兼容地址，填到 /v1 为止",
        ),
    )

    fun byId(id: String): AiProvider? = all.firstOrNull { it.id == id }

    /** 根据已保存的 Base URL 反查是哪家，用于设置页回显选中态 */
    fun matchByUrl(baseUrl: String): AiProvider? {
        val normalized = baseUrl.trim().trimEnd('/')
        if (normalized.isBlank()) return null
        return all.firstOrNull { it.id != "custom" && it.baseUrl.trimEnd('/') == normalized }
    }
}
