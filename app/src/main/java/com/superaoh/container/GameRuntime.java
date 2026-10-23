package com.superaoh.container;

import android.app.Activity;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexClassLoader;

/**
 * 游戏运行时：从用户选择的 APK 提取 DEX、构造引擎实例，并提供对游戏内部静态状态
 * （CFG.* / MenuManager / Menu 枚举）的反射访问。
 *
 * 设计约束：
 *  1. Android 14+ 拒绝加载「仍可写」的动态 DEX。所以必须
 *     解压 -> .tmp -> setReadOnly() -> 原子改名 -> 复检不可写 -> DexClassLoader。
 *  2. ZipFile 的所有权归调用方（游戏资源代理）所有，本类只读 DEX，绝不 close 它。
 */
public final class GameRuntime {

    private static final String TAG = "SuperAoh/GameRuntime";

    public static final String GAME_CLASS =
            "age.of.civilizations2.jakowski.lukasz.AoCGame";
    public static final String LINK_HANDLER_CLASS =
            "age.of.civilizations2.jakowski.lukasz.AndroidLinkHandler";
    public static final String CFG_CLASS =
            "age.of.civilizations2.jakowski.lukasz.CFG";
    public static final String MENU_ENUM_CLASS =
            "age.of.civilizations2.jakowski.lukasz.Menu";

    private static volatile ClassLoader gameLoader;
    private static volatile Class<?> cfgClass;
    private static volatile Object gameInstance;

    private GameRuntime() {
    }

    public static ClassLoader getGameLoader() {
        return gameLoader;
    }

    public static Object getGameInstance() {
        return gameInstance;
    }

    public static final class DeployResult {
        public final List<File> dexFiles;
        public final long totalBytes;

        DeployResult(List<File> dexFiles, long totalBytes) {
            this.dexFiles = dexFiles;
            this.totalBytes = totalBytes;
        }
    }

    // ---------------- 第一步：部署 DEX ----------------

    /**
     * 从 APK 中提取所有 classes*.dex 到私有目录并设为只读。
     * 只读是硬性要求，否则 Android 14+ 会拒绝 DexClassLoader。
     */
    public static DeployResult deployDex(ZipFile apkZip, File codeCache) throws Exception {
        List<String> names = new ArrayList<>();
        java.util.Enumeration<? extends java.util.zip.ZipEntry> it = apkZip.entries();
        while (it.hasMoreElements()) {
            ZipEntry e = it.nextElement();
            String n = e.getName();
            boolean isRootDex = n.indexOf('/') < 0
                    && n.startsWith("classes")
                    && n.endsWith(".dex");
            if (isRootDex) {
                names.add(n);
            }
        }
        if (names.isEmpty()) {
            throw new IllegalStateException("游戏包内没有 classes*.dex");
        }
        // classes.dex, classes2.dex, classes3.dex ... 顺序必须稳定
        Collections.sort(names, (a, b) -> {
            int ia = dexIndex(a);
            int ib = dexIndex(b);
            return Integer.compare(ia, ib);
        });

        File dexDir = new File(codeCache, "game_dex");
        ensureDir(dexDir);

        List<File> out = new ArrayList<>();
        long total = 0;
        for (String name : names) {
            ZipEntry entry = apkZip.getEntry(name);
            File target = new File(dexDir, name);
            boolean need = !target.exists()
                    || target.length() != entry.getSize()
                    || target.canWrite();
            if (need) {
                File temp = new File(dexDir, name + ".tmp");
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                try (InputStream in = apkZip.getInputStream(entry);
                     FileOutputStream os = new FileOutputStream(temp)) {
                    byte[] buf = new byte[512 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                    os.flush();
                }
                if (!temp.setReadOnly()) {
                    throw new SecurityException("无法把临时 DEX 设为只读: " + name);
                }
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                if (!temp.renameTo(target)) {
                    java.nio.file.Files.copy(temp.toPath(), target.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                }
            }
            // 无论新旧路径，都强制只读
            //noinspection ResultOfMethodCallIgnored
            target.setWritable(false, false);
            //noinspection ResultOfMethodCallIgnored
            target.setWritable(false, true);
            //noinspection ResultOfMethodCallIgnored
            target.setReadOnly();
            if (target.canWrite()) {
                throw new SecurityException(
                        "DEX 仍可写，Android 会拒绝动态加载: " + target.getAbsolutePath());
            }
            out.add(target);
            total += target.length();
        }
        Log.i(TAG, "DEX 部署完成: " + out.size() + " 个 / " + total + " 字节");
        return new DeployResult(out, total);
    }

    private static int dexIndex(String name) {
        if ("classes.dex".equals(name)) {
            return 1;
        }
        String mid = name.substring("classes".length(), name.length() - ".dex".length());
        try {
            return Integer.parseInt(mid);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static void ensureDir(File dir) {
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IllegalStateException("无法创建目录: " + dir);
        }
    }

    // ---------------- 第二步：构造游戏实例 ----------------

    /**
     * 用已部署的 DEX 构造 AoCGame 实例。
     * 必须先在宿主 Activity 中完成 AndroidApplication.initializeForView，
     * 因为游戏的 AndroidLinkHandler 会去拿 Gdx.app / Gdx.files 等全局对象。
     */
    public static Object instantiate(Activity host, List<File> dexFiles, File codeCache)
            throws Exception {
        ensureDir(codeCache);
        File optDir = new File(codeCache, "game_dex_opt");
        ensureDir(optDir);

        StringBuilder cp = new StringBuilder();
        for (int i = 0; i < dexFiles.size(); i++) {
            if (i > 0) {
                cp.append(File.pathSeparator);
            }
            cp.append(dexFiles.get(i).getAbsolutePath());
        }

        DexClassLoader loader = new DexClassLoader(
                cp.toString(),
                optDir.getAbsolutePath(),
                null,
                host.getClassLoader());
        gameLoader = loader;

        Class<?> linkHandlerClass = loader.loadClass(LINK_HANDLER_CLASS);
        Object linkHandler = linkHandlerClass
                .getConstructor(Activity.class)
                .newInstance(host);

        Class<?> gameClass = loader.loadClass(GAME_CLASS);
        Class<?> ctorParam = resolveLinkHandlerParam(gameClass, linkHandlerClass);
        Object game = gameClass.getConstructor(ctorParam).newInstance(linkHandler);

        cfgClass = loader.loadClass(CFG_CLASS);
        gameInstance = game;

        Log.i(TAG, "游戏实例构造成功: " + gameClass.getName()
                + " (ctorParam=" + ctorParam.getName() + ")");
        return game;
    }

    /** AoCGame 构造器形参是 LinkHandler 接口，不是 AndroidLinkHandler 本身。 */
    private static Class<?> resolveLinkHandlerParam(Class<?> gameClass, Class<?> linkHandlerClass)
            throws Exception {
        for (java.lang.reflect.Constructor<?> ctor : gameClass.getConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 1 && params[0].isAssignableFrom(linkHandlerClass)) {
                return params[0];
            }
        }
        for (java.lang.reflect.Constructor<?> ctor : gameClass.getConstructors()) {
            if (ctor.getParameterCount() == 1) {
                return ctor.getParameterTypes()[0];
            }
        }
        throw new IllegalStateException("找不到 AoCGame 的单参构造器");
    }

    // ---------------- 反射访问 ----------------

    public static Class<?> cfgClass() {
        return cfgClass;
    }

    public static Object staticField(Class<?> type, String name) {
        if (type == null) {
            return null;
        }
        try {
            Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean hasStaticField(Class<?> type, String name) {
        if (type == null) {
            return false;
        }
        try {
            type.getDeclaredField(name);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Integer intField(Object instance, String name) {
        if (instance == null) {
            return null;
        }
        try {
            Field f = instance.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(instance);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object instanceField(Object instance, String name) {
        if (instance == null) {
            return null;
        }
        try {
            Field f = instance.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(instance);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object menuManager() {
        return staticField(cfgClass, "menuManager");
    }

    public static int currentViewId() {
        Integer v = intField(menuManager(), "viewID");
        return v == null ? -1 : v;
    }

    /** 跳到编辑器主页。游戏内部等价于 CFG.menuManager.setViewID(Menu.eEDITOR)。 */
    public static boolean openEditor() {
        return openMenu("eEDITOR");
    }

    public static boolean openMenu(String menuEnumName) {
        try {
            ClassLoader cl = gameLoader;
            if (cl == null) {
                Log.w(TAG, "游戏尚未加载 (gameLoader=null)");
                return false;
            }
            Class<?> menuEnumClass = cl.loadClass(MENU_ENUM_CLASS);
            Object target = null;
            for (Object c : menuEnumClass.getEnumConstants()) {
                if (((Enum<?>) c).name().equals(menuEnumName)) {
                    target = c;
                    break;
                }
            }
            if (target == null) {
                Log.w(TAG, "Menu 枚举不存在: " + menuEnumName);
                return false;
            }
            Object mm = menuManager();
            if (mm == null) {
                Log.w(TAG, "menuManager 为空，游戏未完成初始化");
                return false;
            }
            Method m = mm.getClass().getDeclaredMethod("setViewID", menuEnumClass);
            m.setAccessible(true);
            m.invoke(mm, target);
            Log.i(TAG, "已跳转菜单: " + menuEnumName);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "跳转菜单失败: " + menuEnumName, t);
            return false;
        }
    }

    /** 编辑器主页是否正在显示。 */
    public static boolean isEditorVisible() {
        try {
            ClassLoader cl = gameLoader;
            if (cl == null) {
                return false;
            }
            Class<?> menuEnumClass = cl.loadClass(MENU_ENUM_CLASS);
            Object editor = null;
            for (Object c : menuEnumClass.getEnumConstants()) {
                if (((Enum<?>) c).name().equals("eEDITOR")) {
                    editor = c;
                    break;
                }
            }
            Object mm = menuManager();
            if (mm == null || editor == null) {
                return false;
            }
            Method getViewId = mm.getClass().getDeclaredMethod("getViewID", menuEnumClass);
            getViewId.setAccessible(true);
            Object v = getViewId.invoke(mm, editor);
            return v instanceof Integer && (Integer) v == currentViewId();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 单行探针，用于界面上实时观测游戏内部状态。
     * 只读 static 字段，不改动任何游戏状态。
     */
    public static String probeLine() {
        try {
            Class<?> cfg = cfgClass;
            if (cfg == null) {
                return "cfg=null";
            }
            boolean g = staticField(cfg, "game") != null;
            boolean m = staticField(cfg, "map") != null;
            boolean f = staticField(cfg, "fontMain") != null;
            boolean l = staticField(cfg, "langManager") != null;
            boolean mm = staticField(cfg, "menuManager") != null;
            boolean em = staticField(cfg, "editorManager") != null;
            return "game=" + g + " map=" + m + " font=" + f + " lang=" + l
                    + " menuMgr=" + mm + " editorMgr=" + em
                    + " viewID=" + currentViewId();
        } catch (Throwable t) {
            return "probe异常: " + t.getClass().getSimpleName();
        }
    }

    /**
     * 游戏是否已经准备好接受「跳转编辑器」。
     *
     * 判据只有两个，都是实测出来的：
     *   1. CFG.game / CFG.map / CFG.fontMain / CFG.menuManager 全部非空
     *      —— 说明引擎资源装配完成，编辑器依赖的 CFG.map / CFG.game 存在。
     *   2. viewID != -1
     *      —— viewID 是 getViewID(Menu) 的返回值，0 是合法编号（主菜单），
     *         -1 才代表 MenuManager 还没有任何有效视图。
     *
     * 注意：不能用「viewID != 0」判断，实测游戏正常跑起来后 viewID 一直是 0。
     */
    public static boolean isGameReady() {
        try {
            Class<?> cfg = cfgClass;
            if (cfg == null) {
                return false;
            }
            if (staticField(cfg, "game") == null
                    || staticField(cfg, "map") == null
                    || staticField(cfg, "fontMain") == null
                    || staticField(cfg, "menuManager") == null) {
                return false;
            }
            return currentViewId() != -1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 运行时诊断，供 UI 的「诊断」按钮显示。 */
    public static String diagnose() {
        StringBuilder sb = new StringBuilder();
        sb.append("gameLoader=").append(gameLoader == null ? "null" : "ok").append('\n');
        sb.append("cfgClass=").append(cfgClass == null ? "null" : cfgClass.getName()).append('\n');
        try {
            sb.append("CFG.game=").append(staticField(cfgClass, "game") != null).append('\n');
            sb.append("CFG.map=").append(staticField(cfgClass, "map") != null).append('\n');
            sb.append("CFG.fontMain=").append(staticField(cfgClass, "fontMain") != null).append('\n');
            sb.append("CFG.langManager=").append(staticField(cfgClass, "langManager") != null).append('\n');
            sb.append("CFG.menuManager=").append(staticField(cfgClass, "menuManager") != null).append('\n');
            sb.append("CFG.editorManager=")
                    .append(hasStaticField(cfgClass, "editorManager")).append('\n');
            sb.append("viewID=").append(currentViewId()).append('\n');
            Object mm = menuManager();
            sb.append("menuManager.class=")
                    .append(mm == null ? "null" : mm.getClass().getName()).append('\n');
            sb.append("isEditorVisible=").append(isEditorVisible()).append('\n');
        } catch (Throwable t) {
            sb.append("诊断异常: ").append(t).append('\n');
        }
        return sb.toString();
    }
}