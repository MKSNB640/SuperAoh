package com.superaoh.core;

import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 游戏 APK 解析器。
 *
 * 用户只选一个 APK，我们负责判断：
 *   1. 这是不是文明时代2 / HoH4 的包（靠 classes.dex 里的关键类名）
 *   2. 里面有哪些 dex、多大
 *   3. assets/ 里有多少资源、有没有 map 和 game 目录
 *   4. 有哪些 lib/*.so（需要提醒用户 libstub.so 不能加载）
 *   5. 计算包指纹（用于工作区隔离与缓存失效）
 *
 * 全部基于 ZipFile 流式扫描，不解压完整 APK，避免占存储。
 */
public final class ApkInspector {

    /** 判定为游戏包必须存在的类（任选其一命中即可）。 */
    private static final String[] REQUIRED_CLASSES = {
            "Lage/of/civilizations2/jakowski/lukasz/AoCGame;",
            "Lage/of/civilizations2/jakowski/lukasz/CFG;",
    };

    /** 编辑器相关关键类，用于判断该包是否裁掉了编辑器。 */
    private static final String[] EDITOR_CLASSES = {
            "Lage/of/civilizations2/jakowski/lukasz/EditorManager;",
            "Lage/of/civilizations2/jakowski/lukasz/Menu_Editor;",
            "Lage/of/civilizations2/jakowski/lukasz/Editor_Wasteland;",
    };

    public static final class Report {
        public File file;
        public long fileSize;
        public String sha256;

        public boolean isGame;
        public String gameClassFound;
        public boolean editorPresent;
        public final List<String> editorClassesFound = new ArrayList<>();

        public final List<String> dexEntries = new ArrayList<>();
        public long dexBytes;

        public int assetFileCount;
        public long assetBytes;
        public boolean hasMapDir;
        public boolean hasGameDir;
        public boolean hasUiDir;
        public final List<String> topLevelAssetDirs = new ArrayList<>();

        public final List<String> nativeLibs = new ArrayList<>();
        public boolean hasStubLib;

        public final List<String> warnings = new ArrayList<>();
        public String error;

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("文件: ").append(file == null ? "?" : file.getName()).append('\n');
            sb.append("大小: ").append(formatSize(fileSize)).append('\n');
            sb.append("SHA256: ").append(sha256 == null ? "?" : sha256.substring(0, Math.min(16, sha256.length()))).append("...\n");
            sb.append("是游戏包: ").append(isGame ? "是" : "否").append('\n');
            if (gameClassFound != null) {
                sb.append("识别类: ").append(gameClassFound).append('\n');
            }
            sb.append("编辑器: ").append(editorPresent ? "完整" : "缺失").append('\n');
            sb.append("DEX: ").append(dexEntries.size()).append(" 个 / ")
                    .append(formatSize(dexBytes)).append('\n');
            sb.append("资源: ").append(assetFileCount).append(" 个 / ")
                    .append(formatSize(assetBytes)).append('\n');
            sb.append("map目录: ").append(hasMapDir ? "有" : "无")
                    .append("  game目录: ").append(hasGameDir ? "有" : "无").append('\n');
            if (!nativeLibs.isEmpty()) {
                sb.append("原生库: ").append(nativeLibs.size()).append(" 个\n");
            }
            if (!warnings.isEmpty()) {
                sb.append("--- 警告 ---\n");
                for (String w : warnings) {
                    sb.append("· ").append(w).append('\n');
                }
            }
            if (error != null) {
                sb.append("--- 错误 ---\n").append(error).append('\n');
            }
            return sb.toString();
        }
    }

    public static Report inspect(File apk, ProgressCallback cb) {
        Report r = new Report();
        r.file = apk;
        try {
            if (!apk.exists() || !apk.isFile()) {
                r.error = "文件不存在: " + apk;
                return r;
            }
            r.fileSize = apk.length();
            if (cb != null) {
                cb.onProgress("计算指纹...", 5);
            }
            r.sha256 = sha256(apk);

            try (ZipFile zip = new ZipFile(apk)) {
                if (cb != null) {
                    cb.onProgress("扫描条目...", 20);
                }
                scanDex(zip, r);
                if (cb != null) {
                    cb.onProgress("检查编辑器...", 55);
                }
                scanEditorClasses(zip, r);
                if (cb != null) {
                    cb.onProgress("统计资源...", 70);
                }
                scanAssets(zip, r);
                scanNativeLibs(zip, r);
            }

            postCheck(r);
            if (cb != null) {
                cb.onProgress("完成", 100);
            }
        } catch (Throwable t) {
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        return r;
    }

    private static void scanDex(ZipFile zip, Report r) {
        java.util.Enumeration<? extends java.util.zip.ZipEntry> it = zip.entries();
        while (it.hasMoreElements()) {
            ZipEntry e = it.nextElement();
            String n = e.getName();
            if (n.indexOf('/') < 0 && n.startsWith("classes") && n.endsWith(".dex")) {
                r.dexEntries.add(n);
                r.dexBytes += e.getSize();
            }
        }
        Collections.sort(r.dexEntries);
    }

    /**
     * 从 dex 的 string pool 里找关键类名。
     * 不解析完整 dex 结构，只做字符串扫描，足够判断且很快。
     */
    private static void scanEditorClasses(ZipFile zip, Report r) {
        for (String dexName : r.dexEntries) {
            ZipEntry entry = zip.getEntry(dexName);
            if (entry == null) {
                continue;
            }
            byte[] data;
            try (InputStream in = zip.getInputStream(entry)) {
                data = readAll(in);
            } catch (Throwable t) {
                continue;
            }
            for (String cls : REQUIRED_CLASSES) {
                if (r.gameClassFound == null && indexOf(data, cls.getBytes()) >= 0) {
                    r.gameClassFound = cls;
                }
            }
            for (String cls : EDITOR_CLASSES) {
                if (indexOf(data, cls.getBytes()) >= 0
                        && !r.editorClassesFound.contains(cls)) {
                    r.editorClassesFound.add(cls);
                }
            }
            if (r.gameClassFound != null && r.editorPresent) {
                break;
            }
        }
        r.isGame = r.gameClassFound != null;
        r.editorPresent = !r.editorClassesFound.isEmpty();
    }

    private static void scanAssets(ZipFile zip, Report r) {
        java.util.Set<String> tops = new java.util.TreeSet<>();
        java.util.Enumeration<? extends java.util.zip.ZipEntry> it = zip.entries();
        while (it.hasMoreElements()) {
            ZipEntry e = it.nextElement();
            String n = e.getName();
            if (!n.startsWith("assets/")) {
                continue;
            }
            r.assetFileCount++;
            r.assetBytes += e.getSize();
            String rest = n.substring("assets/".length());
            int slash = rest.indexOf('/');
            if (slash > 0) {
                tops.add(rest.substring(0, slash));
            }
            if (rest.startsWith("map/")) {
                r.hasMapDir = true;
            }
            if (rest.startsWith("game/")) {
                r.hasGameDir = true;
            }
            if (rest.startsWith("UI/")) {
                r.hasUiDir = true;
            }
        }
        r.topLevelAssetDirs.addAll(tops);
    }

    private static void scanNativeLibs(ZipFile zip, Report r) {
        java.util.Enumeration<? extends java.util.zip.ZipEntry> it = zip.entries();
        while (it.hasMoreElements()) {
            ZipEntry e = it.nextElement();
            String n = e.getName();
            if (n.startsWith("lib/") && n.endsWith(".so")) {
                r.nativeLibs.add(n);
                if (n.endsWith("libstub.so")) {
                    r.hasStubLib = true;
                }
            }
        }
        Collections.sort(r.nativeLibs);
    }

    private static void postCheck(Report r) {
        if (!r.isGame) {
            r.warnings.add("未识别到 AoC2/HoH4 关键类，这个包可能不是文明时代2，"
                    + "或使用了强混淆（类名被改写）。");
        }
        if (r.isGame && !r.editorPresent) {
            r.warnings.add("检测不到编辑器类，该包可能已被裁剪过编辑器，"
                    + "SuperAoh 将无法提供编辑功能。");
        }
        if (r.hasStubLib) {
            r.warnings.add("包含 libstub.so（加壳保护运行时）。"
                    + "SuperAoh 不会加载它，这是刻意的：加载会在 JNI_OnLoad 触发 SIGABRT。");
        }
        if (r.dexEntries.isEmpty()) {
            r.warnings.add("APK 内没有 classes.dex，无法加载。");
        }
        if (r.assetFileCount == 0) {
            r.warnings.add("APK 内没有 assets/，游戏将找不到地图和资源。");
        }
        if (!r.hasMapDir) {
            r.warnings.add("assets/map 缺失，剧本编辑器无法工作（编辑器依赖 CFG.map）。");
        }
    }

    // ---------------- 工具 ----------------

    public interface ProgressCallback {
        void onProgress(String stage, int percent);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        byte first = needle[0];
        int limit = haystack.length - needle.length;
        outer:
        for (int i = 0; i <= limit; i++) {
            if (haystack[i] != first) {
                continue;
            }
            for (int j = 1; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    public static String sha256(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1024 * 256];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    /** 读取全部字节（Java 8 兼容，替代 InputStream.readAllBytes）。 */
    static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private ApkInspector() {
    }
}