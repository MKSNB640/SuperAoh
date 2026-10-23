package com.superaoh.core.skill;

import android.content.Context;
import android.util.Log;

import com.superaoh.core.Json;
import com.superaoh.core.Workspace;
import com.superaoh.core.tool.ToolRegistry;
import com.superaoh.core.tool.ToolResult;
import com.superaoh.core.tool.ToolSpec;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ---------------------------------------------------------------------------
// Skill 系统
//
// 这里的 Skill 与 Operit 的 Skill 是同一概念：一段可复用的「提示词 + 工具」。
// 但落地方式做了简化，因为 Android 上没有轻松可用的 Python/Node 运行时。
//
// 一个 Skill 是一个目录：
//   <skills>/<skill名>/
//       SKILL.json     元数据：name / description / triggers / prompt / always
//       tools.json     可选：该 Skill 提供的工具声明（shell 命令模板）
//       *.md           可选：额外提示词片段，按文件名顺序拼进 prompt
//
// 触发机制（两级，和 Operit 一致）：
//   1. 常驻：always=true 的 Skill 提示词始终注入系统提示词
//   2. 关键词：用户消息命中 triggers 里的任一关键词时注入
//
// 为什么不实现「AI 自己决定加载哪个 Skill」：
//   那需要额外一轮模型调用去判断，成本和延迟都不划算，而且容易误判。
//   关键词匹配在这个规模下足够准，且零延迟、零 token 成本。
// ---------------------------------------------------------------------------

public final class SkillManager {

    private static final String TAG = "SkillManager";

    /** 一个已加载的 Skill。 */
    public static final class Skill {
        public String id = "";
        public String name = "";
        public String description = "";
        /** 触发关键词。空表示不会因关键词触发。 */
        public List<String> triggers = new ArrayList<>();
        /** 是否常驻注入。 */
        public boolean always = false;
        /** 注入到系统提示词的正文。 */
        public String prompt = "";
        /** 提供工具的数量，展示用。 */
        public int toolCount = 0;

        /** 是否被这条用户消息触发。 */
        public boolean matches(String userText) {
            if (always) return true;
            if (userText == null || userText.isEmpty()) return false;
            String low = userText.toLowerCase();
            for (String t : triggers) {
                if (t != null && !t.isEmpty() && low.contains(t.toLowerCase())) {
                    return true;
                }
            }
            return false;
        }
    }

    private final Context appCtx;
    private final ToolRegistry registry;

    /** App 私有目录下的技能根目录。 */
    private final File root;

    private final List<Skill> skills = new ArrayList<>();

    public SkillManager(Context ctx, ToolRegistry registry) {
        this.appCtx = ctx.getApplicationContext();
        this.registry = registry;
        this.root = resolveRoot(appCtx);
        ensure(root);
    }

    /**
     * 技能根目录：优先外部存储，不可用则退回私有目录。
     *
     * 与插件同理 —— 技能是用户要自己写的 Markdown + JSON，
     * 放在私有目录里用户根本够不到，等于没有这个功能。
     * 落点是 /sdcard/Android/data/<pkg>/files/SuperAoh/skills。
     */
    private static File resolveRoot(Context ctx) {
        File ext = ctx.getExternalFilesDir(null);
        if (ext != null) {
            File dir = new File(ext, "SuperAoh/skills");
            if (dir.exists() || dir.mkdirs()) {
                return dir;
            }
            Log.w(TAG, "外部技能目录不可写，退回私有目录: " + dir);
        }
        return new File(ctx.getFilesDir(), "skills");
    }

    public File root() {
        return root;
    }

    public synchronized List<Skill> skills() {
        return new ArrayList<>(skills);
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    /**
     * 扫描技能目录，全部重新加载。
     *
     * 采用「全量重扫」而不是增量：技能目录通常只有几个条目，
     * 全量扫描成本极低，而增量要处理删除/改名/部分加载失败，容易出状态不一致。
     */
    public synchronized int loadAll() {
        skills.clear();
        // 先清掉旧的 Skill 工具，避免删掉技能后工具残留
        registry.unregisterBySource(ToolRegistry.SRC_SKILL);

        if (!root.isDirectory()) {
            Log.i(TAG, "技能目录不存在: " + root);
            return 0;
        }

        File[] dirs = root.listFiles();
        if (dirs == null) return 0;

        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            try {
                Skill s = loadOne(d);
                if (s != null) skills.add(s);
            } catch (Throwable t) {
                // 单个技能坏了不该影响其他技能
                Log.w(TAG, "加载技能失败 " + d.getName() + ": " + t.getMessage());
            }
        }
        Log.i(TAG, "已加载 " + skills.size() + " 个技能");
        return skills.size();
    }

    /** 加载一个技能目录。 */
    private Skill loadOne(File dir) throws Exception {
        File meta = new File(dir, "SKILL.json");
        if (!meta.isFile()) return null;

        Map<String, Object> m = Json.parseObject(readText(meta));
        Skill s = new Skill();
        s.id = dir.getName();
        s.name = strOr(Json.str(m, "name"), dir.getName());
        s.description = strOr(Json.str(m, "description"), "");
        Object al = m.get("always");
        s.always = al instanceof Boolean && (Boolean) al;

        List<Object> trig = Json.arr(m, "triggers");
        if (trig != null) {
            for (Object o : trig) {
                if (o != null) s.triggers.add(String.valueOf(o));
            }
        }

        StringBuilder prompt = new StringBuilder();
        String inline = Json.str(m, "prompt");
        if (inline != null && !inline.isEmpty()) {
            prompt.append(inline).append("\n\n");
        }

        // 额外的 .md 片段，按文件名排序拼接（01-xxx.md 在 02-yyy.md 之前）
        File[] mds = dir.listFiles();
        if (mds != null) {
            List<File> sorted = new ArrayList<>();
            for (File f : mds) {
                if (f.isFile() && f.getName().toLowerCase().endsWith(".md")) {
                    sorted.add(f);
                }
            }
            java.util.Collections.sort(sorted, new java.util.Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File f : sorted) {
                prompt.append("--- ").append(f.getName()).append(" ---\n");
                prompt.append(readText(f)).append("\n\n");
            }
        }
        s.prompt = prompt.toString().trim();

        // 工具声明
        File toolsFile = new File(dir, "tools.json");
        if (toolsFile.isFile()) {
            s.toolCount = loadTools(dir, Json.parseObject(readText(toolsFile)));
        }
        return s;
    }

    /**
     * 加载技能提供的工具。
     *
     * 工具定义格式（每条是一个 shell 命令模板）：
     *   {
     *     "tools": [
     *       {
     *         "name": "my_tool",
     *         "description": "做什么",
     *         "command": "echo {msg}",       // {param} 会被参数替换
     *         "params": {"msg": "要输出的内容"}
     *       }
     *     ]
     *   }
     *
     * 为什么用 shell 模板而不是内嵌脚本语言：
     *   shell 是设备上一定有的东西，且能被 proot 沙箱执行。
     *   内嵌 JS/Python 需要额外运行时，体积和兼容性都不划算。
     */
    @SuppressWarnings("unchecked")
    private int loadTools(File skillDir, Map<String, Object> root) {
        List<Object> list = Json.arr(root, "tools");
        if (list == null) return 0;

        int n = 0;
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) o;
            String name = Json.str(m, "name");
            String cmd = Json.str(m, "command");
            if (name == null || name.isEmpty() || cmd == null || cmd.isEmpty()) continue;

            String desc = strOr(Json.str(m, "description"), "");
            Map<String, String> params = new LinkedHashMap<>();
            Map<String, Object> pm = Json.obj(m, "params");
            if (pm != null) {
                for (Map.Entry<String, Object> e : pm.entrySet()) {
                    params.put(e.getKey(), String.valueOf(e.getValue()));
                }
            }

            registry.register(new SkillTool(skillDir.getName() + "__" + name, desc, params, cmd,
                    new ArrayList<>(params.keySet())));
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------
    // 提示词注入
    // ------------------------------------------------------------------

    /**
     * 收集所有被触发的技能提示词。
     *
     * @param userText 用户本轮输入，用于关键词匹配；null 表示只看常驻技能
     * @return 拼好的提示词块，无触发时返回空串
     */
    public synchronized String buildPromptBlock(String userText) {
        StringBuilder sb = new StringBuilder();
        for (Skill s : skills) {
            if (!s.matches(userText)) continue;
            if (s.prompt.isEmpty() && s.toolCount == 0) continue;
            sb.append("\n\n【技能: ").append(s.name).append("】\n");
            if (!s.description.isEmpty()) {
                sb.append(s.description).append('\n');
            }
            if (!s.prompt.isEmpty()) {
                sb.append(s.prompt).append('\n');
            }
        }
        return sb.toString();
    }

    /** 当前会被注入的技能名，UI 展示用。 */
    public synchronized List<String> activeNames(String userText) {
        List<String> out = new ArrayList<>();
        for (Skill s : skills) {
            if (s.matches(userText)) out.add(s.name);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static String strOr(String s, String def) {
        return (s == null || s.isEmpty()) ? def : s;
    }

    private static void ensure(File d) {
        if (d != null && !d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
    }

    private static String readText(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(4096);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 2 * 1024 * 1024) break; // 单个技能文件不该超过 2MB
            }
            return bos.toString("UTF-8");
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------------
    // 技能工具
    // ------------------------------------------------------------------

    /** 由技能声明出来的工具：把参数填进 shell 模板再执行。 */
    static final class SkillTool implements ToolSpec {

        private final String name;
        private final String description;
        private final Map<String, String> params;
        private final String template;
        private final List<String> required;

        SkillTool(String name, String description, Map<String, String> params,
                  String template, List<String> required) {
            this.name = name;
            this.description = description;
            this.params = params;
            this.template = template;
            this.required = required;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public Map<String, String> params() {
            return params;
        }

        @Override
        public List<String> required() {
            return required;
        }

        @Override
        public String source() {
            return ToolRegistry.SRC_SKILL;
        }

        @Override
        public boolean needsWorkspace() {
            return false;
        }

        @Override
        public ToolResult execute(Map<String, Object> args, Workspace ws, Context ctx) {
            String cmd = template;
            for (Map.Entry<String, String> e : params.entrySet()) {
                String key = e.getKey();
                Object v = args == null ? null : args.get(key);
                String val = v == null ? "" : String.valueOf(v);
                // 做 shell 单引号转义，防止参数里的引号把命令拆坏
                cmd = cmd.replace("{" + key + "}", shellQuote(val));
            }
            try {
                Process p = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                        .redirectErrorStream(true)
                        .start();
                java.io.InputStream in = p.getInputStream();
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(4096);
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 512 * 1024) break;
                }
                int code = p.waitFor();
                String out = bos.toString("UTF-8").trim();
                return code == 0
                        ? ToolResult.ok(out.isEmpty() ? "(无输出)" : out)
                        : ToolResult.fail("退出码 " + code + "\n" + out);
            } catch (Throwable t) {
                return ToolResult.fail("执行失败: " + t.getMessage());
            }
        }

        /** POSIX 单引号转义。 */
        private static String shellQuote(String s) {
            if (s == null) return "''";
            return "'" + s.replace("'", "'\\''") + "'";
        }
    }
}