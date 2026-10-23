package com.superaoh.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 服务配置。
 *
 * 支持多个「配置档」（profile），因为用户可能同时有官方 API、中转站、本地模型。
 * 每个档包含：名称 / BaseUrl / ApiKey / 模型名 / 温度 / 最大 token。
 *
 * 安全说明：
 *   这里用 SharedPreferences 明文存 Key，没有引入 EncryptedSharedPreferences，
 *   因为那需要 androidx.security 依赖，会破坏「零依赖单 dex」的构建约束。
 *   当前数据在应用私有目录（/data/data/com.superaoh/shared_prefs/），
 *   非 root 设备上其他应用读不到。后续可以加一层 AES 混淆。
 */
public final class AiConfig {

    private static final String PREFS = "superaoh_ai";
    private static final String KEY_PROFILES = "profiles";
    private static final String KEY_ACTIVE = "activeProfile";
    private static final String KEY_SYSTEM = "systemPrompt";

    /** 单个配置档。 */
    public static final class Profile {
        public String id;
        public String name;
        public String baseUrl;
        public String apiKey;
        public String model;
        public double temperature;
        public int maxTokens;

        /**
         * 上下文窗口（token）。用于历史裁剪与用量估算。
         * 0 或负数表示「未知」，此时不按窗口裁剪，只保留固定的最大消息条数。
         */
        public int contextWindow;

        /** 单次回复上限（token）。与 maxTokens 同义，保留字段便于区分。 */
        public int topP;
        /**
         * 思考等级对应的请求字段名。默认 reasoning_effort。
         * 少数网关用别的名字（如 thinking），所以做成可配置。
         */
        public String reasoningEffortField = "reasoning_effort";
        public Profile() {
        }

        public Profile(String id, String name, String baseUrl, String apiKey,
                       String model, double temperature, int maxTokens) {
            this.id = id;
            this.name = name;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
            this.temperature = temperature;
            this.maxTokens = maxTokens;
            this.contextWindow = 0;
            this.topP = 0;
        }

        /** 补全到 /chat/completions 的完整地址。 */
        public String chatUrl() {
            String base = baseUrl == null ? "" : baseUrl.trim();
            if (base.isEmpty()) {
                return "";
            }
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            if (base.endsWith("/chat/completions")) {
                return base;
            }
            return base + "/chat/completions";
        }

        /**
         * 推导 /models 地址。
         *
         * 各家写法不一致：
         *   https://api.openai.com/v1                   -> /v1/models
         *   https://api.openai.com/v1/chat/completions  -> /v1/models
         *   https://xxx/v1/                              -> /v1/models
         * 规则：先剥掉 /chat/completions，再拼 /models。
         */
        public String modelsUrl() {
            String base = baseUrl == null ? "" : baseUrl.trim();
            if (base.isEmpty()) {
                return "";
            }
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            String suffix = "/chat/completions";
            if (base.endsWith(suffix)) {
                base = base.substring(0, base.length() - suffix.length());
            }
            if (base.endsWith("/models")) {
                return base;
            }
            return base + "/models";
        }

        /** 是否配置完整可用。 */
        public boolean usable() {
            return baseUrl != null && !baseUrl.trim().isEmpty()
                    && model != null && !model.trim().isEmpty();
        }

        public String displayLine() {
            return name + " · " + model;
        }

        public Profile copy() {
            Profile p = new Profile(id, name, baseUrl, apiKey, model,
                    temperature, maxTokens);
            p.contextWindow = contextWindow;
            p.topP = topP;
            p.reasoningEffortField = reasoningEffortField;
            return p;
        }
    }

    private AiConfig() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------- 配置档管理 ----------------

    public static List<Profile> listProfiles(Context ctx) {
        String raw = prefs(ctx).getString(KEY_PROFILES, "");
        List<Profile> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            out.add(defaultProfile());
            return out;
        }
        try {
            Object parsed = Json.parse(raw);
            if (parsed instanceof List) {
                for (Object o : (List<?>) parsed) {
                    Profile p = fromMap(o);
                    if (p != null) {
                        out.add(p);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (out.isEmpty()) {
            out.add(defaultProfile());
        }
        return out;
    }

    private static Profile defaultProfile() {
        return new Profile("default", "默认", "", "", "", 0.7, 4096);
    }

    @SuppressWarnings("unchecked")
    private static Profile fromMap(Object o) {
        if (!(o instanceof java.util.Map)) {
            return null;
        }
        java.util.Map<String, Object> m = (java.util.Map<String, Object>) o;
        Profile p = new Profile();
        p.id = Json.str(m, "id");
        p.name = Json.str(m, "name");
        p.baseUrl = Json.str(m, "baseUrl");
        p.apiKey = Json.str(m, "apiKey");
        p.model = Json.str(m, "model");
        Object t = m.get("temperature");
        p.temperature = t instanceof Number ? ((Number) t).doubleValue() : 0.7;
        Object mx = m.get("maxTokens");
        p.maxTokens = mx instanceof Number ? ((Number) mx).intValue() : 4096;
        Object cw = m.get("contextWindow");
        p.contextWindow = cw instanceof Number ? ((Number) cw).intValue() : 0;
        Object tp = m.get("topP");
        p.topP = tp instanceof Number ? ((Number) tp).intValue() : 0;
        String ref = Json.str(m, "reasoningEffortField");
        p.reasoningEffortField = (ref == null || ref.trim().isEmpty())
                ? "reasoning_effort" : ref.trim();
        if (p.id == null) {
            p.id = "p" + System.currentTimeMillis();
        }
        if (p.name == null) {
            p.name = p.model == null ? "未命名" : p.model;
        }
        return p;
    }

    public static void saveProfiles(Context ctx, List<Profile> profiles) {
        List<Object> arr = new ArrayList<>();
        for (Profile p : profiles) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", p.id);
            m.put("name", p.name);
            m.put("baseUrl", p.baseUrl);
            m.put("apiKey", p.apiKey);
            m.put("model", p.model);
            m.put("temperature", p.temperature);
            m.put("maxTokens", p.maxTokens);
            m.put("contextWindow", p.contextWindow);
            m.put("topP", p.topP);
            m.put("reasoningEffortField", p.reasoningEffortField);
            arr.add(m);
        }
        prefs(ctx).edit().putString(KEY_PROFILES, Json.write(arr)).apply();
    }

    public static Profile getActive(Context ctx) {
        String id = prefs(ctx).getString(KEY_ACTIVE, null);
        List<Profile> all = listProfiles(ctx);
        if (id != null) {
            for (Profile p : all) {
                if (id.equals(p.id)) {
                    return p;
                }
            }
        }
        return all.isEmpty() ? defaultProfile() : all.get(0);
    }

    public static void setActive(Context ctx, String profileId) {
        prefs(ctx).edit().putString(KEY_ACTIVE, profileId).apply();
    }

    // ---------------- 系统提示词 ----------------

    public static String getSystemPrompt(Context ctx) {
        String s = prefs(ctx).getString(KEY_SYSTEM, null);
        return s == null ? defaultSystemPrompt() : s;
    }

    public static void setSystemPrompt(Context ctx, String prompt) {
        prefs(ctx).edit().putString(KEY_SYSTEM, prompt).apply();
    }

    /**
     * 默认系统提示词。
     * 明确告诉模型它能改什么、不能改什么，减少它乱造工具名。
     */
    public static String defaultSystemPrompt() {
        return "你是 SuperAoh 的内置 AI 助手，运行在 Android 设备上的《文明时代2》"
                + "（Age of History II / AoC2）编辑器容器里。\n\n"
                + "你的能力边界：\n"
                + "1. 你可以读取当前工作区的游戏包信息、资源目录、DEX 列表。\n"
                + "2. 你可以读写工作区内的文件（存档、资源覆盖层、补丁目录）。\n"
                + "3. 你不能直接修改已加载的游戏内存，只能通过文件与 DEX 补丁生效。\n\n"
                + "规则：\n"
                + "· 需要信息时先调用工具，不要凭猜测回答。\n"
                + "· 涉及删除或覆盖文件前，先向用户说明将要做什么。\n"
                + "· 回答用简体中文，简洁直接，不要复述工具返回的原始 JSON。";
    }
}