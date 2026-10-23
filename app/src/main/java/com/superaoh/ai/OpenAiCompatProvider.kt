package com.superaoh.ai

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容 Provider —— 覆盖绝大多数中转站与本地模型。
 *
 * 适配范围（实测有效）：
 *   - OpenAI 官方、NewAPI / OneAPI 中转站（含当前用的 cf.sheapi.cc）
 *   - DeepSeek、Kimi、Qwen、GLM、Mimo、Mistral、Nvidia、OpenRouter
 *   - Ollama / LM Studio / vLLM（OpenAI 兼容模式）
 *
 * 设计要点（借鉴 Operit 的 OpenAIProvider，但去掉其 OkHttp 依赖）：
 *   - 用 HttpURLConnection 直接读 SSE，避免引入 OkHttp
 *   - tool_calls 按 index 增量累加，最后一次性交付（Operit 同款做法）
 *   - reasoning_content 与 content 分开推送，UI 可折叠展示
 *   - 区分可重试 / 不可重试错误，交由 RetryPolicy 处理
 */
class OpenAiCompatProvider : LlmProvider {

    override fun id(): String = "openai-compat"

    override fun capabilities(): LlmCapabilities = LlmCapabilities(
        supportsTools = true,
        supportsVision = true,
        supportsStream = true,
        reasoningField = "reasoning_content",
        visionStyle = LlmCapabilities.VISION_OPENAI,
        toolRole = "tool"
    )

    override fun stream(
        req: LlmRequest,
        messagesJson: String,
        listener: LlmStreamListener
    ): Boolean {
        var conn: HttpURLConnection? = null
        try {
            val body = JSONObject().apply {
                put("model", req.model)
                put("messages", org.json.JSONArray(messagesJson))
                put("temperature", req.temperature)
                put("max_tokens", req.maxTokens)
                put("top_p", req.topP)
                put("stream", req.stream)
            }

            // 注意：https 返回的是 HttpsURLConnection（HttpURLConnection 的子类），
            // 但 openConnection() 静态类型是 URLConnection，必须强转为
            // HttpURLConnection 才能设置请求头/方法——这在 Java 8 桌面 JVM
            // 上会抛 ClassCastException，正确写法是先 cast 到 HttpURLConnection。
            val raw = URL(req.url).openConnection()
            conn = raw as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                connectTimeout = 30000
                readTimeout = 180000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (!req.apiKey.isNullOrEmpty()) {
                    setRequestProperty("Authorization", "Bearer " + req.apiKey)
                }
                for ((k, v) in req.headers) {
                    setRequestProperty(k, v)
                }
            }

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code < 200 || code > 299) {
                val err = try {
                    conn.errorStream?.let { s ->
                        BufferedReader(InputStreamReader(s, Charsets.UTF_8)).readText()
                    } ?: ""
                } catch (t: Throwable) {
                    ""
                }
                val retryable = code >= 429 || code == 408
                listener.onError(
                    "HTTP " + code + ": " + (if (err.isEmpty()) (conn.responseMessage ?: "") else err.take(500)),
                    retryable
                )
                return false
            }

            if (!req.stream) {
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                parseNonStream(text, listener)
                return true
            }

            readSse(conn, listener)
            return true
        } catch (t: Throwable) {
            listener.onError(t.message ?: t.javaClass.simpleName, isRetryable(t))
            return false
        } finally {
            try {
                conn?.disconnect()
            } catch (ignored: Throwable) {
            }
        }
    }

    // ------------------------------------------------------------
    // SSE 解析
    // ------------------------------------------------------------

    private fun readSse(conn: HttpURLConnection, listener: LlmStreamListener) {
        val reader = BufferedReader(
            InputStreamReader(conn.inputStream, Charsets.UTF_8), 8192
        )

        // tool_calls 增量累加：index -> [id, name, args]
        val toolBuf = HashMap<Int, Array<StringBuilder>>()
        var finishReason = "stop"
        var promptTokens = 0
        var completionTokens = 0
        var cachedTokens = 0
        var reasoningTokens = 0
        var errored = false

        // 解析 usage 里两个嵌套明细。
        //   prompt_tokens_details.cached_tokens  —— DeepSeek/OpenAI 的缓存命中
        //   completion_tokens_details.reasoning_tokens —— 推理模型思考消耗
        // 不同厂商字段名有出入，几个常见别名都试一遍，取到非零就用。
        fun readUsage(u: JSONObject?) {
            if (u == null) return
            promptTokens = u.optInt("prompt_tokens", promptTokens)
            completionTokens = u.optInt("completion_tokens", completionTokens)

            val pd = u.optJSONObject("prompt_tokens_details")
            val cached = pd?.optInt("cached_tokens", 0) ?: 0
            if (cached > 0) cachedTokens = cached
            // 部分中转站直接平铺在 usage 上
            if (cached <= 0) {
                val flat = u.optInt("cached_tokens", 0)
                if (flat > 0) cachedTokens = flat
                else {
                    val hit = u.optInt("prompt_cache_hit_tokens", 0)
                    if (hit > 0) cachedTokens = hit
                }
            }

            val cd = u.optJSONObject("completion_tokens_details")
            val rt = cd?.optInt("reasoning_tokens", 0) ?: 0
            if (rt > 0) reasoningTokens = rt
        }

        reader.useLines { lines ->
            for (raw in lines) {
                if (!raw.startsWith("data:")) {
                    continue
                }
                val payload = raw.substring(5).trim()
                if (payload.isEmpty()) {
                    continue
                }
                if (payload == "[DONE]") {
                    break
                }
                val obj = try {
                    JSONObject(payload)
                } catch (t: Throwable) {
                    continue
                }

                if (obj.has("error")) {
                    val e = obj.optJSONObject("error")
                    val msg = if (e != null) e.optString("message") else obj.optString("error")
                    listener.onError(msg, false)
                    errored = true
                    break
                }

                val choices = obj.optJSONArray("choices")
                val choice = if (choices != null) choices.optJSONObject(0) else null
                if (choice == null) {
                    readUsage(obj.optJSONObject("usage"))
                    continue
                }

                val delta = choice.optJSONObject("delta")
                if (delta != null) {
                    val content = delta.optString("content", "")
                    if (content.isNotEmpty()) {
                        listener.onContent(content)
                    }
                    val reasoning = delta.optString("reasoning_content", "")
                    if (reasoning.isNotEmpty()) {
                        listener.onReasoning(reasoning)
                    }

                    val calls = delta.optJSONArray("tool_calls")
                    if (calls != null) {
                        for (i in 0 until calls.length()) {
                            val c = calls.optJSONObject(i) ?: continue
                            val idx = c.optInt("index", i)
                            var slot = toolBuf[idx]
                            if (slot == null) {
                                slot = arrayOf(StringBuilder(), StringBuilder(), StringBuilder())
                                toolBuf.put(idx, slot)
                            }
                            if (c.has("id")) {
                                slot[0].setLength(0)
                                slot[0].append(c.optString("id"))
                            }
                            val fn = c.optJSONObject("function")
                            if (fn != null) {
                                if (fn.has("name")) {
                                    slot[1].setLength(0)
                                    slot[1].append(fn.optString("name"))
                                }
                                if (fn.has("arguments")) {
                                    slot[2].append(fn.optString("arguments"))
                                }
                            }
                        }
                    }
                }

                val fr = choice.optString("finish_reason", "")
                if (fr.isNotEmpty() && fr != "null") {
                    finishReason = fr
                }

                val usage = obj.optJSONObject("usage")
                if (usage != null) {
                    readUsage(usage)
                }
            }
        }

        if (errored) {
            return
        }

        for (idx in toolBuf.keys.sorted()) {
            val slot = toolBuf[idx] ?: continue
            val name = slot[1].toString()
            if (name.isEmpty()) {
                continue
            }
            var args = slot[2].toString()
            if (args.isEmpty()) {
                args = "{}"
            }
            listener.onToolCall(slot[0].toString(), name, args)
        }

        if (promptTokens > 0 || completionTokens > 0) {
            listener.onUsage(promptTokens, completionTokens)
            if (cachedTokens > 0 || reasoningTokens > 0) {
                listener.onUsageDetail(cachedTokens, reasoningTokens)
            }
        }
        listener.onEnd(finishReason)
    }

    // ------------------------------------------------------------
    // 非流式解析
    // ------------------------------------------------------------

    private fun parseNonStream(text: String, listener: LlmStreamListener) {
        val obj = try {
            JSONObject(text)
        } catch (t: Throwable) {
            listener.onError("响应不是合法 JSON: " + text.take(200), false)
            return
        }
        if (obj.has("error")) {
            val e = obj.optJSONObject("error")
            listener.onError(if (e != null) e.optString("message") else obj.optString("error"), false)
            return
        }
        val choices = obj.optJSONArray("choices")
        val choice = if (choices != null) choices.optJSONObject(0) else null
        if (choice == null) {
            listener.onError("响应缺少 choices 字段", false)
            return
        }
        val msg = choice.optJSONObject("message")
        if (msg != null) {
            val content = msg.optString("content", "")
            if (content.isNotEmpty()) {
                listener.onContent(content)
            }
            val reasoning = msg.optString("reasoning_content", "")
            if (reasoning.isNotEmpty()) {
                listener.onReasoning(reasoning)
            }
            val calls = msg.optJSONArray("tool_calls")
            if (calls != null) {
                for (i in 0 until calls.length()) {
                    val c = calls.optJSONObject(i) ?: continue
                    val fn = c.optJSONObject("function") ?: continue
                    listener.onToolCall(
                        c.optString("id"),
                        fn.optString("name"),
                        fn.optString("arguments", "{}")
                    )
                }
            }
        }
        val usage = obj.optJSONObject("usage")
        if (usage != null) {
            listener.onUsage(
                usage.optInt("prompt_tokens", 0),
                usage.optInt("completion_tokens", 0)
            )
        }
        listener.onEnd(choice.optString("finish_reason", "stop"))
    }

    private fun isRetryable(t: Throwable): Boolean {
        val name = t.javaClass.simpleName
        return name.contains("Timeout") || name.contains("Socket") ||
            name.contains("Connect") || name.contains("UnknownHost")
    }
}