package com.superaoh.core;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话持久化。
 *
 * 解决的问题：
 *   之前 AiChatActivity 一关闭，Agent 里的 messages 就随对象销毁而丢失，
 *   重新打开是空对话。模型也因此「没有记忆」。
 *
 * 存储位置：
 *   {外部私有目录}/SuperAoh/workspaces/{wsId}/sessions/
 *       {sessionId}.json   单个会话
 *       index.json         会话列表（标题、时间、消息数）
 *
 * 为什么按工作区分别存：
 *   不同工作区对应不同游戏包，混在一起会让 AI 带着 A 存档的上下文
 *   去回答 B 存档的问题。按工作区隔离更符合直觉。
 *
 * 也没有工作区时：
 *   落到 {外部私有目录}/SuperAoh/sessions/，保证纯聊天也能存住。
 *
 * 格式选择 JSON 而不是二进制：
 *   便于用户导出、便于跨版本兼容、出问题时能直接看。
 */
public final class ChatStore {

    private static final String INDEX_FILE = "index.json";
    private static final String SESSIONS_DIR = "sessions";

    /** 会话摘要（用于列表展示）。 */
    public static final class Meta {
        public String id;
        public String title;
        public long updatedAt;
        public int messageCount;

        public Meta() {
        }

        public Meta(String id, String title, long updatedAt, int messageCount) {
            this.id = id;
            this.title = title;
            this.updatedAt = updatedAt;
            this.messageCount = messageCount;
        }
    }

    /** 一条持久化消息。字段与 Agent.Message 对齐，另加图片与时间。 */
    public static final class StoredMessage {
        public String role;
        public String content;
        public String reasoning;
        /** 仅 role=tool */
        public String toolCallId;
        public String toolName;
        /** 工具是否执行成功（用于渲染配色） */
        public boolean toolOk;
        /** 工具参数摘要（用于渲染） */
        public String toolArgs;
        /** 仅 assistant 带工具调用时：JSON 原文数组 */
        public String toolCallsJson;
        /** 用户消息附带的图片（本地文件绝对路径，逗号分隔） */
        public String imagePaths;
        /**
         * 用户消息附带的普通文件。
         *
         * 存成「路径\t名称\t大小\tMIME」每行一条：
         * 只用制表符分行，文件名里可能带逗号/空格，不能用逗号分隔。
         */
        public String fileMeta;
        public long time;

        /**
         * token 用量。
         *
         * 存盘是为了重开应用后气泡尾部的「↑输入 ↓输出」还在。
         * 用 int 而不是 Integer：0 就表示「服务端没给」，不需要区分 null。
         */
        public int promptTokens;
        public int completionTokens;
        public int cachedTokens;
        public int reasoningTokens;
        public long durationMs = -1L;
        public long firstTokenMs = -1L;

        public StoredMessage() {
        }

        public StoredMessage(String role, String content) {
            this.role = role;
            this.content = content;
            this.time = System.currentTimeMillis();
        }

        public List<String> images() {
            List<String> out = new ArrayList<>();
            if (imagePaths == null || imagePaths.trim().isEmpty()) {
                return out;
            }
            for (String s : imagePaths.split("\n")) {
                if (!s.trim().isEmpty()) {
                    out.add(s.trim());
                }
            }
            return out;
        }

        public void setImages(List<String> list) {
            if (list == null || list.isEmpty()) {
                imagePaths = null;
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (String s : list) {
                if (s == null || s.trim().isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(s.trim());
            }
            imagePaths = sb.length() == 0 ? null : sb.toString();
        }

        /**
         * 解析普通文件附件。
         *
         * 格式：每行 `路径\t名称\t大小\tMIME`。
         * 用制表符而不是逗号：安卓文件名里逗号很常见（"存档,备份.zip"）。
         */
        public List<FileItem> files() {
            List<FileItem> out = new ArrayList<>();
            if (fileMeta == null || fileMeta.trim().isEmpty()) {
                return out;
            }
            for (String line : fileMeta.split("\n")) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length < 2 || parts[0].trim().isEmpty()) {
                    continue;
                }
                FileItem item = new FileItem();
                item.path = parts[0];
                item.name = parts[1];
                if (parts.length > 2) {
                    try {
                        item.size = Long.parseLong(parts[2].trim());
                    } catch (NumberFormatException ignored) {
                        item.size = 0L;
                    }
                }
                if (parts.length > 3) {
                    item.mime = parts[3];
                }
                out.add(item);
            }
            return out;
        }

        public void setFiles(List<FileItem> list) {
            if (list == null || list.isEmpty()) {
                fileMeta = null;
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (FileItem f : list) {
                if (f == null || f.path == null || f.path.trim().isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(f.path).append('\t')
                        .append(f.name == null ? "" : f.name.replace('\t', ' ').replace('\n', ' '))
                        .append('\t').append(f.size)
                        .append('\t').append(f.mime == null ? "" : f.mime);
            }
            fileMeta = sb.length() == 0 ? null : sb.toString();
        }
    }

    /** 一个普通文件附件的存储形态。 */
    public static final class FileItem {
        public String path;
        public String name;
        public long size;
        public String mime;
    }

    /** 一个完整会话。 */
    public static final class Session {
        public String id;
        public String title;
        public long createdAt;
        public long updatedAt;
        public String workspaceId;
        /** 用于把图片等附件放在会话旁边的目录里 */
        public final List<StoredMessage> messages = new ArrayList<>();
    }

    private ChatStore() {
    }

    // ============================================================
    // 目录
    // ============================================================

    private static File sessionsDir(Context ctx, String wsId) {
        File base;
        if (wsId != null && !wsId.trim().isEmpty()) {
            base = new File(Workspace.workspacesDir(ctx), wsId);
        } else {
            base = Workspace.root(ctx);
        }
        File dir = new File(base, SESSIONS_DIR);
        Workspace.ensureDir(dir);
        return dir;
    }

    /** 该会话的图片附件目录。 */
    public static File imageDir(Context ctx, String wsId, String sessionId) {
        File dir = new File(sessionsDir(ctx, wsId), sessionId + "_img");
        Workspace.ensureDir(dir);
        return dir;
    }

    /**
     * 该会话的普通文件附件目录。
     *
     * 与图片分开：图片目录会被 ImageUtil 按图片规则处理，
     * 混进 APK/zip 之类的大文件既拖慢扫描又容易误判。
     */
    public static File fileDir(Context ctx, String wsId, String sessionId) {
        File dir = new File(sessionsDir(ctx, wsId), sessionId + "_file");
        Workspace.ensureDir(dir);
        return dir;
    }

    // ============================================================
    // 读写会话
    // ============================================================

    /** 列出会话摘要，按最近更新倒序。 */
    public static List<Meta> listSessions(Context ctx, String wsId) {
        List<Meta> out = new ArrayList<>();
        File idx = new File(sessionsDir(ctx, wsId), INDEX_FILE);
        if (!idx.exists()) {
            return out;
        }
        try {
            String raw = readAll(idx);
            Object parsed = Json.parse(raw);
            if (!(parsed instanceof List)) {
                return out;
            }
            for (Object o : (List<?>) parsed) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<String, Object> m = castMap(o);
                Meta meta = new Meta();
                meta.id = Json.str(m, "id");
                meta.title = Json.str(m, "title");
                Object t = m.get("updatedAt");
                meta.updatedAt = t instanceof Number ? ((Number) t).longValue() : 0L;
                Object c = m.get("messageCount");
                meta.messageCount = c instanceof Number ? ((Number) c).intValue() : 0;
                if (meta.id != null) {
                    out.add(meta);
                }
            }
        } catch (Throwable ignored) {
        }
        // 最近更新的排前面
        java.util.Collections.sort(out, new TimeComparator());
        return out;
    }

    /** 比较器。具名静态类：避免匿名内部类。 */
    static final class TimeComparator implements java.util.Comparator<Meta> {
        @Override
        public int compare(Meta a, Meta b) {
            if (a.updatedAt == b.updatedAt) {
                return 0;
            }
            return a.updatedAt > b.updatedAt ? -1 : 1;
        }
    }

    /** 加载单个会话；不存在返回 null。 */
    public static Session load(Context ctx, String wsId, String sessionId) {
        if (sessionId == null) {
            return null;
        }
        File f = new File(sessionsDir(ctx, wsId), sessionId + ".json");
        if (!f.exists()) {
            return null;
        }
        try {
            Object parsed = Json.parse(readAll(f));
            if (!(parsed instanceof Map)) {
                return null;
            }
            Map<String, Object> m = castMap(parsed);
            Session s = new Session();
            s.id = Json.str(m, "id");
            s.title = Json.str(m, "title");
            s.workspaceId = Json.str(m, "workspaceId");
            Object ca = m.get("createdAt");
            s.createdAt = ca instanceof Number ? ((Number) ca).longValue() : 0L;
            Object ua = m.get("updatedAt");
            s.updatedAt = ua instanceof Number ? ((Number) ua).longValue() : 0L;

            Object list = m.get("messages");
            if (list instanceof List) {
                for (Object o : (List<?>) list) {
                    if (!(o instanceof Map)) {
                        continue;
                    }
                    Map<String, Object> mm = castMap(o);
                    StoredMessage sm = new StoredMessage();
                    sm.role = Json.str(mm, "role");
                    sm.content = Json.str(mm, "content");
                    sm.reasoning = Json.str(mm, "reasoning");
                    sm.toolCallId = Json.str(mm, "toolCallId");
                    sm.toolName = Json.str(mm, "toolName");
                    sm.toolArgs = Json.str(mm, "toolArgs");
                    sm.toolCallsJson = Json.str(mm, "toolCallsJson");
                    sm.imagePaths = Json.str(mm, "imagePaths");
                    sm.fileMeta = Json.str(mm, "fileMeta");
                    sm.promptTokens = Json.intVal(mm, "promptTokens", 0);
                    sm.completionTokens = Json.intVal(mm, "completionTokens", 0);
                    sm.cachedTokens = Json.intVal(mm, "cachedTokens", 0);
                    sm.reasoningTokens = Json.intVal(mm, "reasoningTokens", 0);
                    sm.durationMs = Json.longVal(mm, "durationMs", -1L);
                    sm.firstTokenMs = Json.longVal(mm, "firstTokenMs", -1L);
                    Object ok = mm.get("toolOk");
                    sm.toolOk = ok instanceof Boolean && (Boolean) ok;
                    Object tm = mm.get("time");
                    sm.time = tm instanceof Number ? ((Number) tm).longValue() : 0L;
                    if (sm.role != null) {
                        s.messages.add(sm);
                    }
                }
            }
            if (s.id == null) {
                s.id = sessionId;
            }
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 保存会话并更新索引。
     *
     * 每次保存都重建索引而不是增量更新，是为了避免索引与实际文件不同步
     * （例如用户手动删了某个 json）。会话数量不大，全量重建成本可接受。
     */
    public static void save(Context ctx, String wsId, Session session) {
        if (session == null || session.id == null) {
            return;
        }
        session.updatedAt = System.currentTimeMillis();
        if (session.createdAt == 0) {
            session.createdAt = session.updatedAt;
        }
        if (session.title == null || session.title.trim().isEmpty()) {
            session.title = deriveTitle(session);
        }

        File dir = sessionsDir(ctx, wsId);
        File target = new File(dir, session.id + ".json");

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", session.id);
        root.put("title", session.title);
        root.put("workspaceId", wsId);
        root.put("createdAt", session.createdAt);
        root.put("updatedAt", session.updatedAt);

        List<Object> msgs = new ArrayList<>();
        for (StoredMessage sm : session.messages) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("role", sm.role);
            if (sm.content != null) {
                o.put("content", sm.content);
            }
            if (sm.reasoning != null && !sm.reasoning.isEmpty()) {
                o.put("reasoning", sm.reasoning);
            }
            if (sm.toolCallId != null) {
                o.put("toolCallId", sm.toolCallId);
            }
            if (sm.toolName != null) {
                o.put("toolName", sm.toolName);
            }
            if (sm.toolArgs != null) {
                o.put("toolArgs", sm.toolArgs);
            }
            if (sm.toolCallsJson != null) {
                o.put("toolCallsJson", sm.toolCallsJson);
            }
            if (sm.imagePaths != null) {
                o.put("imagePaths", sm.imagePaths);
            }
            if (sm.fileMeta != null) {
                o.put("fileMeta", sm.fileMeta);
            }
            if (sm.promptTokens > 0 || sm.completionTokens > 0) {
                o.put("promptTokens", sm.promptTokens);
                o.put("completionTokens", sm.completionTokens);
                if (sm.cachedTokens > 0) {
                    o.put("cachedTokens", sm.cachedTokens);
                }
                if (sm.reasoningTokens > 0) {
                    o.put("reasoningTokens", sm.reasoningTokens);
                }
            }
            if (sm.durationMs >= 0) {
                o.put("durationMs", sm.durationMs);
            }
            if (sm.firstTokenMs >= 0) {
                o.put("firstTokenMs", sm.firstTokenMs);
            }
            if (sm.toolOk) {
                o.put("toolOk", Boolean.TRUE);
            }
            o.put("time", sm.time);
            msgs.add(o);
        }
        root.put("messages", msgs);

        try {
            writeAll(target, Json.write(root));
            rebuildIndex(ctx, wsId);
        } catch (Throwable ignored) {
        }
    }

    /** 删除会话及其图片目录。 */
    public static void delete(Context ctx, String wsId, String sessionId) {
        if (sessionId == null) {
            return;
        }
        File dir = sessionsDir(ctx, wsId);
        File f = new File(dir, sessionId + ".json");
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        deleteRecursive(new File(dir, sessionId + "_img"));
        rebuildIndex(ctx, wsId);
    }

    /** 新建一个空会话对象（尚未落盘）。 */
    public static Session newSession(String wsId) {
        Session s = new Session();
        s.id = "s" + System.currentTimeMillis();
        s.workspaceId = wsId;
        s.createdAt = System.currentTimeMillis();
        s.updatedAt = s.createdAt;
        s.title = "新对话";
        return s;
    }

    /** 用第一条用户消息生成标题。 */
    private static String deriveTitle(Session s) {
        for (StoredMessage m : s.messages) {
            if ("user".equals(m.role) && m.content != null
                    && !m.content.trim().isEmpty()) {
                String t = m.content.trim().replace('\n', ' ');
                return t.length() > 24 ? t.substring(0, 24) + "…" : t;
            }
        }
        return "新对话";
    }

    private static void rebuildIndex(Context ctx, String wsId) {
        File dir = sessionsDir(ctx, wsId);
        File[] files = dir.listFiles();
        List<Object> arr = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                if (!name.endsWith(".json") || INDEX_FILE.equals(name)) {
                    continue;
                }
                if (!f.isFile()) {
                    continue;
                }
                Session s = load(ctx, wsId, name.substring(0, name.length() - 5));
                if (s == null) {
                    continue;
                }
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", s.id);
                o.put("title", s.title);
                o.put("updatedAt", s.updatedAt);
                o.put("messageCount", s.messages.size());
                arr.add(o);
            }
        }
        try {
            writeAll(new File(dir, INDEX_FILE), Json.write(arr));
        } catch (Throwable ignored) {
        }
    }

    // ============================================================
    // 文件工具
    // ============================================================

    private static String readAll(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    private static void writeAll(File f, String content) throws Exception {
        File parent = f.getParentFile();
        if (parent != null) {
            Workspace.ensureDir(parent);
        }
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(f), "UTF-8")) {
            w.write(content);
            w.flush();
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRecursive(k);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }
}