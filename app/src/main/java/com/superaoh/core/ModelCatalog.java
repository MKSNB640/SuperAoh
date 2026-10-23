package com.superaoh.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模型列表拉取。
 *
 * 绝大多数 OpenAI 兼容服务都实现了 GET /v1/models，但返回结构有差异：
 *
 *   · 标准 OpenAI / 大多数中转站
 *       { "data": [ { "id": "gpt-4o", "owned_by": "openai" }, ... ] }
 *
 *   · 某些国产网关（如部分 DeepSeek / 通义代理）
 *       { "data": [ { "model": "deepseek-chat" }, ... ] }
 *       或
 *       { "models": [ "deepseek-chat", ... ] }
 *
 *   · 少数直接返回数组
 *       [ "gpt-4o", "gpt-4o-mini" ]
 *
 * 这个类把以上形态统一解析成 String 列表并去重排序。
 * 失败时返回带 error 的结果，由 UI 决定是否回退到手动输入。
 */
public final class ModelCatalog {

    /** 拉取结果。 */
    public static final class Result {
        public final List<String> models;
        public final String error;
        /** 原始响应，出错时方便排查。 */
        public final String raw;

        Result(List<String> models, String error, String raw) {
            this.models = models;
            this.error = error;
            this.raw = raw;
        }

        public boolean ok() {
            return error == null;
        }
    }

    private ModelCatalog() {
    }

    /**
     * 同步拉取模型列表。必须在后台线程调用。
     *
     * @param baseUrl 接口根地址（会自动推导 /models）
     * @param apiKey  密钥，可为空
     */
    public static Result fetch(String baseUrl, String apiKey) {
        AiConfig.Profile tmp = new AiConfig.Profile();
        tmp.baseUrl = baseUrl;
        String url = tmp.modelsUrl();

        if (url == null || url.trim().isEmpty()) {
            return new Result(Collections.<String>emptyList(), "BaseURL 为空", null);
        }

        Http.Response resp = Http.get(url, apiKey, 15000, 25000);
        if (!resp.ok()) {
            return new Result(Collections.<String>emptyList(),
                    resp.describe(), resp.body);
        }

        List<String> parsed = parse(resp.body);
        if (parsed.isEmpty()) {
            return new Result(parsed, "响应里没有解析出任何模型", resp.body);
        }
        return new Result(parsed, null, resp.body);
    }

    /** 解析 /models 响应，兼容多种结构。 */
    public static List<String> parse(String body) {
        List<String> out = new ArrayList<>();
        if (body == null || body.trim().isEmpty()) {
            return out;
        }

        Object root;
        try {
            root = Json.parse(body);
        } catch (Throwable t) {
            return out;
        }

        collect(root, out, 0);

        // 去重（保持顺序）后排序，方便用户查找
        List<String> unique = new ArrayList<>();
        for (String s : out) {
            if (s != null && !s.trim().isEmpty() && !unique.contains(s)) {
                unique.add(s);
            }
        }
        Collections.sort(unique, String.CASE_INSENSITIVE_ORDER);
        return unique;
    }

    /**
     * 递归收集模型名。
     *
     * 用递归而不是逐层判断，是因为不同服务的嵌套深度不一样，
     * 但最终「模型名」总是出现在某个字符串值上。
     */
    @SuppressWarnings("unchecked")
    private static void collect(Object node, List<String> out, int depth) {
        if (node == null || depth > 6) {
            return;
        }

        if (node instanceof List) {
            for (Object item : (List<Object>) node) {
                collect(item, out, depth + 1);
            }
            return;
        }

        if (node instanceof java.util.Map) {
            java.util.Map<String, Object> m = (java.util.Map<String, Object>) node;

            // 优先取标准字段
            String s = Json.str(m, "id");
            if (s == null || s.trim().isEmpty()) {
                s = Json.str(m, "model");
            }
            if (s == null || s.trim().isEmpty()) {
                s = Json.str(m, "name");
            }
            if (s != null && !s.trim().isEmpty()) {
                out.add(s.trim());
                return; // 拿到就不往更深层走，避免把 owned_by 之类当成模型名
            }

            // 没有直接命中，继续往容器字段里找
            for (String key : new String[]{"data", "models", "items", "result"}) {
                Object child = m.get(key);
                if (child != null) {
                    collect(child, out, depth + 1);
                }
            }
            return;
        }

        if (node instanceof String) {
            out.add(((String) node).trim());
        }
    }
}