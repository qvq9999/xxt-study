package com.xxt.study.core.ai

import com.xxt.study.core.crypto.SecretStore
import com.xxt.study.core.net.Http
import com.xxt.study.core.net.HttpText
import com.xxt.study.core.result.Cx
import com.xxt.study.domain.Question
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * AI 答题客户端（OpenAI 兼容接口）。
 *
 * 为什么不用原项目那套 dashscope SDK + 自建题库：
 * - SDK 是几百 KB 的 Java 依赖，只为发一个 HTTP 请求不值
 * - 题库要另建服务，手机端反而多一个不可控依赖
 * 这里只认标准 `/chat/completions`，通义、DeepSeek、Kimi、自建 ollama 都能填。
 *
 * 密钥走 [SecretStore]（AndroidKeyStore 加密），不落明文、不进日志。
 */
class AiClient(
    private val secrets: SecretStore,
    /** 只在单纯问答题时用；不复用刷课那串口，避免互相拖慢 */
    private val http: Http,
) {

    /**
     * 一次整卷作答的结果。
     *
     * 除了答案本身，还带回 [reasoning] 与 [raw]：
     * 用户要看"AI 是怎么想的"，出问题时也只有原始输出能定位是模型没答还是解析没认出来。
     */
    data class AiResult(
        val answers: Map<String, List<String>>,
        val reasoning: String?,
        val raw: String,
    )

    data class Config(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
    ) {
        val usable: Boolean
            get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

        val endpoint: String
            get() = baseUrl.trim().trimEnd('/') + "/chat/completions"
    }

    /**
     * 配置按**服务商分开存**。
     *
     * 原来只有一个全局槽位，换预设就把上一个的 Key 冲掉了——
     * 用户手上有 DeepSeek 和 Agnes 两个 Key 时，来回切换等于每次都重填。
     * 现在每个预设各存一份，active 只记录"当前用哪个"。
     */
    fun draft(providerId: String): Config? {
        val base = secrets.get(keyBase(providerId))
        val key = secrets.get(keyKey(providerId))
        val model = secrets.get(keyModel(providerId))
        if (base == null && key == null && model == null) return null
        return Config(base.orEmpty(), key.orEmpty(), model.orEmpty())
    }

    /** 只写当前这个预设的槽位，并且把它设为 active；别的预设一个字都不动 */
    fun saveFor(providerId: String, c: Config): Boolean {
        var ok = secrets.put(keyBase(providerId), c.baseUrl.trim())
        ok = secrets.put(keyKey(providerId), c.apiKey.trim()) && ok
        ok = secrets.put(keyModel(providerId), c.model.trim()) && ok
        if (ok) secrets.put(KEY_ACTIVE, providerId)
        return ok
    }

    fun activeProviderId(): String {
        secrets.get(KEY_ACTIVE)?.takeIf { it.isNotBlank() }?.let { return it }
        return migrateLegacy()
    }

    fun setActive(providerId: String) {
        secrets.put(KEY_ACTIVE, providerId)
    }

    /**
     * 一个"配齐了"的服务：界面上的快捷切换项。
     *
     * 只收 Base URL / Key / 模型三项都非空的——半配完的在列表里点了也用不了，
     * 反而让人以为切换失败。
     */
    data class Saved(val id: String, val label: String, val model: String)

    /**
     * 已保存、可直接使用的服务列表。
     *
     * 每次都要读密钥库，不要在重组里直接调；调用方用 `remember(版本号)` 缓存。
     */
    fun savedProviders(): List<Saved> = AiProviders.all.mapNotNull { p ->
        val c = draft(p.id) ?: return@mapNotNull null
        if (!c.usable) return@mapNotNull null
        Saved(p.id, p.label, c.model)
    }

    /** 当前正在用的服务；没配齐就返回 null */
    fun activeSaved(): Saved? {
        val id = activeProviderId()
        return savedProviders().firstOrNull { it.id == id }
    }

    /** 给界面显示的一行字 */
    fun activeLabel(): String = activeSaved()?.let { "${it.label} · ${it.model}" } ?: "未配置模型"

    /** 答题时用：当前预设必须三项齐全 */
    fun config(): Config? {
        val c = draft(activeProviderId()) ?: return null
        return if (c.usable) c else null
    }

    fun clear() {
        AiProviders.all.forEach { p ->
            secrets.remove(keyBase(p.id))
            secrets.remove(keyKey(p.id))
            secrets.remove(keyModel(p.id))
        }
        secrets.remove(KEY_ACTIVE)
        secrets.remove(LEGACY_BASE)
        secrets.remove(LEGACY_KEY)
        secrets.remove(LEGACY_MODEL)
    }

    private fun keyBase(id: String) = "ai_" + id + "_base"
    private fun keyKey(id: String) = "ai_" + id + "_key"
    private fun keyModel(id: String) = "ai_" + id + "_model"

    /** 老版本只有一个全局槽位，第一次读时搬到对应预设下，别让用户重填 */
    private fun migrateLegacy(): String {
        val oldBase = secrets.get(LEGACY_BASE)?.trim().orEmpty()
        if (oldBase.isBlank()) {
            secrets.put(KEY_ACTIVE, DEFAULT_PROVIDER)
            return DEFAULT_PROVIDER
        }
        val pid = AiProviders.matchByUrl(oldBase)?.id ?: CUSTOM_PROVIDER
        saveFor(
            pid,
            Config(
                baseUrl = oldBase,
                apiKey = secrets.get(LEGACY_KEY).orEmpty(),
                model = secrets.get(LEGACY_MODEL).orEmpty(),
            ),
        )
        secrets.remove(LEGACY_BASE)
        secrets.remove(LEGACY_KEY)
        secrets.remove(LEGACY_MODEL)
        return pid
    }

    /**
     * 一次性把整卷交给模型，返回 `题号 → 答案数组`。
     *
     * 整卷一次问完而不是逐题问：既省 token（同一门课的题往往同源），
     * 也让模型能利用卷内的上下文。代价是单次请求更大，用 120 秒超时兜住。
     */
    suspend fun answer(questions: List<Question>): Cx<AiResult> {
        if (questions.isEmpty()) return Cx.Ok(AiResult(emptyMap(), null, ""))
        val cfg = config() ?: return Cx.Fail(Cx.Kind.DENIED, "还没配置 AI 接口，先去设置里填 Base URL / 密钥 / 模型")

        val payload = buildJsonObject {
            put("model", cfg.model)
            put("temperature", 0)
            // 不给上限时走服务端默认（常见 512~1024），整卷答案一多就被截断，
            // 表现就是"简答题字数不够"或某题凭空消失
            put("max_tokens", 4096)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", AiPrompt.SYSTEM)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", AiPrompt.user(questions))
                })
            })
        }.toString()

        return withContext(Dispatchers.IO) {
            try {
                withTimeout(120_000) {
                    val resp = postJson(cfg, payload)
                    if (resp.code !in 200..299) {
                        return@withTimeout Cx.Fail(Cx.Kind.DENIED, "AI 接口返回 HTTP ${resp.code}：${resp.body.take(160)}")
                    }
                    parseAnswers(resp.body, questions)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Cx.Fail(Cx.Kind.NETWORK, "AI 接口超时（120s）")
            } catch (e: IOException) {
                Cx.Fail(Cx.Kind.NETWORK, e.message ?: "AI 接口连接失败")
            } catch (e: Exception) {
                Cx.Fail(Cx.Kind.UNKNOWN, e.message ?: e::class.java.simpleName)
            }
        }
    }

    private suspend fun postJson(cfg: Config, payload: String): HttpText {
        // AI 端点可能要几十秒，不能用刷课那套 20 秒读超时
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder()
            .url(cfg.endpoint)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .header("Content-Type", "application/json; charset=utf-8")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return client.newCall(req).execute().use {
            HttpText(it.code, it.request.url.toString(), it.body?.string().orEmpty())
        }
    }

    /**
     * 连通性探测：发一条最小请求，看能不能拿回 content。
     *
     * 返回一句给人看的话，不抛异常——设置页要的是"能不能用 + 为什么不能用"。
     * 常见失败原因（401 密钥错、404 路径错、模型名不存在）都在这里直接说清楚，
     * 比等到答题时才发现要省事得多。
     */
    /**
     * 单题重写：整卷答完后发现某道简答题字数不达标，单独请模型重写这一题。
     *
     * 为什么不整卷重来：成本高，还可能把已经答对的题改坏。单题重写能把
     * "你只写了 98 字，要求 150-250 字"直接顶到模型面前，命中率高得多。
     */
    suspend fun answerOne(q: Question, rule: LengthRule, previous: String): Cx<String> =
        withContext(Dispatchers.IO) {
            val cfg = config() ?: return@withContext Cx.Fail(Cx.Kind.DENIED, "AI 未配置")
            val payload = buildJsonObject {
                put("model", cfg.model)
                put("temperature", 0.3)
                put("max_tokens", 2048)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", "你是答题助手。只输出答案正文，不要题号、不要解释、不要 Markdown、不要引号。")
                    })
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", AiPrompt.rewrite(q, rule, previous))
                    })
                })
            }.toString()

            try {
                withTimeout(90_000) {
                    val resp = postJson(cfg, payload)
                    if (resp.code !in 200..299) {
                        return@withTimeout Cx.Fail(Cx.Kind.DENIED, "重写请求失败 HTTP " + resp.code)
                    }
                    val content = runCatching {
                        json.parseToJsonElement(resp.body).jsonObject
                            .getValue("choices").jsonArray[0].jsonObject
                            .getValue("message").jsonObject
                            .getValue("content").jsonPrimitive.content
                    }.getOrNull()?.trim().orEmpty()
                    /*
                     * 这里必须清洗。
                     *
                     * 重写的 user 消息几乎全是"要求"，模型一顺着说就把要求当成了答案，
                     * 而那段文字常常刚好落在字数区间内 —— 直接回填等于把提示词写进作答框。
                     * 清洗后为 null 就当作"这次重写失败"，保留上一版（宁可短，也不能塞垃圾）。
                     */
                    val clean = AnswerSanitizer.sanitize(content, q.stem)
                    when {
                        content.isBlank() -> Cx.Fail(Cx.Kind.PARSE, "模型返回空答案")
                        clean == null -> Cx.Fail(Cx.Kind.PARSE, "模型复述了要求或照抄题干，没有真正作答")
                        else -> Cx.Ok(clean)
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Cx.Fail(Cx.Kind.NETWORK, "重写超时")
            } catch (e: Exception) {
                Cx.Fail(Cx.Kind.UNKNOWN, e.message ?: e::class.java.simpleName)
            }
        }

    suspend fun probe(cfg: Config): String = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("model", cfg.model)
            put("temperature", 0)
            // 不给上限时走服务端默认（常见 512~1024），整卷答案一多就被截断，
            // 表现就是"简答题字数不够"或某题凭空消失
            put("max_tokens", 8)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "回复两个字：可用")
                })
            })
        }.toString()
        try {
            withTimeout(60_000) {
                val resp = postJson(cfg, payload)
                when {
                    resp.code == 401 || resp.code == 403 ->
                        "密钥无效（HTTP ${resp.code}），检查 API Key"
                    resp.code == 404 ->
                        "路径不对（HTTP 404），Base URL 应填到 /v1 为止"
                    resp.code !in 200..299 ->
                        "接口返回 HTTP ${resp.code}：${resp.body.take(120)}"
                    else -> {
                        val content = runCatching {
                            json.parseToJsonElement(resp.body).jsonObject
                                .getValue("choices").jsonArray[0].jsonObject
                                .getValue("message").jsonObject
                                .getValue("content").jsonPrimitive.content
                        }.getOrNull()
                        if (content.isNullOrBlank()) {
                            "能连上，但响应里没有 content，可能不是 OpenAI 兼容接口"
                        } else {
                            "连接正常，模型回复：${content.take(20)}"
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            "连接超时（60s），检查网络或 Base URL"
        } catch (e: IOException) {
            "连接失败：${e.message ?: "网络异常"}"
        } catch (e: Exception) {
            "测试失败：${e.message ?: e::class.java.simpleName}"
        }
    }

    /**
     * 扫描可用模型：`GET {base}/models`。
     *
     * OpenAI 兼容服务商基本都实现了这个端点，返回 `{"data":[{"id":"xxx"},...]}`。
     * 少数只做 chat 的网关不实现，会返回 404 —— 这时如实告诉用户"手填模型名"，
     * 不要伪装成"连不上"，否则用户会一直怀疑是 Key 的问题。
     */
    suspend fun listModels(baseUrl: String, apiKey: String): Cx<List<String>> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext Cx.Fail(Cx.Kind.DENIED, "先填 Base URL")
        if (apiKey.isBlank()) return@withContext Cx.Fail(Cx.Kind.DENIED, "先填 API Key")

        val url = baseUrl.trim().trimEnd('/') + "/models"
        try {
            withTimeout(30_000) {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${apiKey.trim()}")
                    .header("Accept", "application/json")
                    .get()
                    .build()
                val resp = client.newCall(req).execute().use { r ->
                    HttpText(r.code, r.request.url.toString(), r.body?.string().orEmpty())
                }

                when {
                    resp.code == 401 || resp.code == 403 ->
                        return@withTimeout Cx.Fail(Cx.Kind.DENIED, "Key 无效（HTTP ${resp.code}）")
                    resp.code == 404 ->
                        return@withTimeout Cx.Fail(
                            Cx.Kind.DENIED,
                            "这家没有 /models 端点，模型名请手填",
                        )
                    resp.code !in 200..299 ->
                        return@withTimeout Cx.Fail(Cx.Kind.DENIED, "HTTP ${resp.code}：${resp.body.take(120)}")
                }

                val root = runCatching { json.parseToJsonElement(resp.body).jsonObject }.getOrNull()
                    ?: return@withTimeout Cx.Fail(Cx.Kind.PARSE, "响应不是 JSON")
                val arr = root["data"] as? kotlinx.serialization.json.JsonArray
                    ?: return@withTimeout Cx.Fail(Cx.Kind.PARSE, "响应里没有 data 数组")

                val ids = arr.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    obj["id"]?.jsonPrimitive?.content?.trim()?.ifBlank { null }
                }.distinct().sorted()

                if (ids.isEmpty()) {
                    Cx.Fail(Cx.Kind.PARSE, "模型列表是空的，可能这个 Key 没有可用模型")
                } else {
                    Cx.Ok(ids)
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Cx.Fail(Cx.Kind.NETWORK, "扫描超时（30s）")
        } catch (e: IOException) {
            Cx.Fail(Cx.Kind.NETWORK, e.message ?: "连接失败")
        } catch (e: Exception) {
            Cx.Fail(Cx.Kind.UNKNOWN, e.message ?: e::class.java.simpleName)
        }
    }

    /** 从 OpenAI 兼容响应里抠出 content，再解析成答案表 */
    fun parseAnswers(body: String, questions: List<Question> = emptyList()): Cx<AiResult> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return Cx.Fail(Cx.Kind.PARSE, "AI 响应不是 JSON")
        val message = runCatching {
            root["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
        }.getOrNull() ?: return Cx.Fail(Cx.Kind.PARSE, "AI 响应结构异常（缺 choices[0].message）")

        val content = runCatching { message["content"]!!.jsonPrimitive.content }.getOrNull()
            ?: return Cx.Fail(Cx.Kind.PARSE, "AI 响应里没有 message.content")

        // 带思考过程的模型（DeepSeek reasoner / MiMo 等）会额外给 reason 字段
        val reasoning = runCatching {
            (message["reasoning_content"] ?: message["reasoning"])?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() }

        val jsonText = stripFence(content)
        val obj = runCatching { json.parseToJsonElement(jsonText).jsonObject }.getOrNull()
            ?: return Cx.Fail(Cx.Kind.PARSE, "AI 没按要求输出 JSON：${content.take(120)}")

        val answers = obj["answers"]?.jsonArray
            ?: return Cx.Fail(Cx.Kind.PARSE, "AI 输出缺少 answers 字段")

        val stemOf = questions.associate { it.qid to it.stem }

        val out = LinkedHashMap<String, List<String>>()
        for (item in answers) {
            val q = item as? JsonObject ?: continue
            val id = q["id"]?.jsonPrimitive?.content?.trim() ?: continue
            val list = runCatching { q["answer"]!!.jsonArray.map { it.jsonPrimitive.content.trim() } }
                .getOrNull() ?: continue
            /*
             * 逐条过一遍清洗。
             *
             * 模型偶尔会把回答写成一整段"要求复述"（尤其是整卷 JSON 被 max_tokens 截断后
             * 补出来的内容），直接回填等于把提示词写进作答框。见 AnswerSanitizer。
             */
            val cleaned = list.mapNotNull { AnswerSanitizer.sanitize(it, stemOf[id].orEmpty()) }
                .filter { it.isNotBlank() }
            if (cleaned.isNotEmpty()) out[id] = cleaned
        }
        if (out.isEmpty()) return Cx.Fail(Cx.Kind.PARSE, "AI 没有给出任何答案")
        return Cx.Ok(AiResult(out, reasoning, content))
    }

    private fun stripFence(text: String): String {
        var s = text.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            if (s.endsWith("```")) s = s.dropLast(3)
        }
        // 有的模型喜欢在 JSON 前后加说明，截取第一个 { 到最后一个 }
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        return if (a >= 0 && b > a) s.substring(a, b + 1) else s.trim()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private companion object {
        const val KEY_ACTIVE = "ai_active_provider"
        const val LEGACY_BASE = "ai_base_url"
        const val LEGACY_KEY = "ai_api_key"
        const val LEGACY_MODEL = "ai_model"
        const val DEFAULT_PROVIDER = "custom"
        const val CUSTOM_PROVIDER = "custom"

    }
}
