package com.superaoh.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * 工作区（Workspace）管理。
 *
 * 一个工作区 = 一个游戏包 + 一套独立存档 + 一套独立补丁。
 * 这样用户可以同时持有原版、联机版、自制版互不干扰。
 *
 * 目录布局：
 *   <externalFilesDir>/SuperAoh/
 *   ├── workspaces/<id>/workspace.properties   元数据
 *   ├── workspaces/<id>/game.apk               游戏包（复制或软链）
 *   ├── workspaces/<id>/data/                  Gdx.files.local 存档根
 *   ├── workspaces/<id>/overlay/assets/        资源补丁覆盖层
 *   ├── workspaces/<id>/patches/*.dex          DEX 补丁
 *   └── cache/hot/<fingerprint>/               热点资源缓存
 */
public final class Workspace {

    private static final String ROOT = "SuperAoh";
    private static final String PREFS = "superaoh";
    private static final String KEY_ACTIVE = "activeWorkspace";

    public String id;
    public String name;
    public String apkPath;
    public String apkSha256;
    public long apkSize;
    public boolean editorPresent;
    public long createdAt;

    public Workspace() {
    }

    // ---------------- 路径 ----------------

    public static File root(Context ctx) {
        File dir = new File(ctx.getExternalFilesDir(null), ROOT);
        ensureDir(dir);
        return dir;
    }

    public static File workspacesDir(Context ctx) {
        File dir = new File(root(ctx), "workspaces");
        ensureDir(dir);
        return dir;
    }

    public static File cacheRoot(Context ctx) {
        File dir = new File(root(ctx), "cache");
        ensureDir(dir);
        return dir;
    }

    public File dir(Context ctx) {
        return new File(workspacesDir(ctx), id);
    }

    public File apk(Context ctx) {
        return new File(dir(ctx), "game.apk");
    }

    public File dataDir(Context ctx) {
        File d = new File(dir(ctx), "data");
        ensureDir(d);
        return d;
    }

    public File overlayAssetsDir(Context ctx) {
        File d = new File(dir(ctx), "overlay/assets");
        ensureDir(d);
        return d;
    }

    public File patchesDir(Context ctx) {
        File d = new File(dir(ctx), "patches");
        ensureDir(d);
        return d;
    }

    public File assetCacheDir(Context ctx) {
        File d = new File(dir(ctx), "asset_extract");
        ensureDir(d);
        return d;
    }

    public File hotCacheDir(Context ctx) {
        if (apkSha256 == null) {
            return null;
        }
        File d = new File(cacheRoot(ctx), "hot/" + apkSha256.substring(0, 16));
        ensureDir(d);
        return d;
    }

    // ---------------- 持久化 ----------------

    public void save(Context ctx) {
        File f = new File(dir(ctx), "workspace.properties");
        Properties p = new Properties();
        p.setProperty("id", id == null ? "" : id);
        p.setProperty("name", name == null ? "" : name);
        p.setProperty("apkPath", apkPath == null ? "" : apkPath);
        p.setProperty("apkSha256", apkSha256 == null ? "" : apkSha256);
        p.setProperty("apkSize", String.valueOf(apkSize));
        p.setProperty("editorPresent", String.valueOf(editorPresent));
        p.setProperty("createdAt", String.valueOf(createdAt));
        try (FileOutputStream out = new FileOutputStream(f)) {
            p.store(out, "SuperAoh workspace");
        } catch (Throwable ignored) {
        }
    }

    public static Workspace load(Context ctx, String id) {
        File f = new File(new File(workspacesDir(ctx), id), "workspace.properties");
        if (!f.exists()) {
            return null;
        }
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(f)) {
            p.load(in);
        } catch (Throwable t) {
            return null;
        }
        Workspace w = new Workspace();
        w.id = p.getProperty("id", id);
        w.name = p.getProperty("name", id);
        w.apkPath = p.getProperty("apkPath", "");
        w.apkSha256 = p.getProperty("apkSha256", "");
        w.apkSize = parseLong(p.getProperty("apkSize"), 0);
        w.editorPresent = Boolean.parseBoolean(p.getProperty("editorPresent", "false"));
        w.createdAt = parseLong(p.getProperty("createdAt"), 0);
        return w;
    }

    public static java.util.List<Workspace> listAll(Context ctx) {
        java.util.List<Workspace> out = new java.util.ArrayList<>();
        File[] dirs = workspacesDir(ctx).listFiles();
        if (dirs == null) {
            return out;
        }
        java.util.Arrays.sort(dirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File d : dirs) {
            if (!d.isDirectory()) {
                continue;
            }
            Workspace w = load(ctx, d.getName());
            if (w != null) {
                out.add(w);
            }
        }
        return out;
    }

    public static void delete(Context ctx, String id) {
        deleteRecursive(new File(workspacesDir(ctx), id));
        if (id.equals(getActiveId(ctx))) {
            setActiveId(ctx, null);
        }
    }

    // ---------------- 当前工作区 ----------------

    public static String getActiveId(Context ctx) {
        return prefs(ctx).getString(KEY_ACTIVE, null);
    }

    public static void setActiveId(Context ctx, String id) {
        prefs(ctx).edit().putString(KEY_ACTIVE, id).apply();
    }

    public static Workspace getActive(Context ctx) {
        String id = getActiveId(ctx);
        return id == null ? null : load(ctx, id);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------- 导入 ----------------

    public interface Progress {
        void onProgress(String stage, int percent);
    }

    /**
     * 从用户选中的 APK 创建新工作区。
     * 会复制 APK 到工作区（隔离），并写入元数据。
     */
    public static Workspace importApk(Context ctx, File sourceApk,
                                      ApkInspector.Report report, Progress cb) throws Exception {
        String id = "ws_" + System.currentTimeMillis();
        Workspace w = new Workspace();
        w.id = id;
        w.name = sourceApk.getName();
        w.apkSha256 = report != null ? report.sha256 : ApkInspector.sha256(sourceApk);
        w.apkSize = sourceApk.length();
        w.editorPresent = report != null && report.editorPresent;
        w.createdAt = System.currentTimeMillis();

        File dir = w.dir(ctx);
        ensureDir(dir);

        if (cb != null) {
            cb.onProgress("复制游戏包...", 10);
        }
        File target = w.apk(ctx);
        copyFile(sourceApk, target);
        w.apkPath = target.getAbsolutePath();

        if (cb != null) {
            cb.onProgress("初始化目录...", 80);
        }
        w.dataDir(ctx);
        w.overlayAssetsDir(ctx);
        w.patchesDir(ctx);
        w.save(ctx);
        setActiveId(ctx, w.id);

        if (cb != null) {
            cb.onProgress("完成", 100);
        }
        return w;
    }

    // ---------------- 工具 ----------------

    public static void ensureDir(File dir) {
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IllegalStateException("无法创建目录: " + dir.getAbsolutePath());
        }
    }

    public static void copyFile(File src, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null) {
            ensureDir(parent);
        }
        try (InputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[512 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    public static void deleteRecursive(File f) {
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

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s);
        } catch (Throwable t) {
            return def;
        }
    }
}