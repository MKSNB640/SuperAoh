package com.superaoh.container;

import com.badlogic.gdx.ApplicationListener;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 容器桥接：把 SuperAoh 宿主的 GL 生命周期转发给「从用户 APK 加载出来的」游戏引擎实例。
 *
 * 为什么需要这层：
 *   游戏入口 AoCGame 是 libGDX ApplicationAdapter 子类，它期望自己就是整个 App 的
 *   ApplicationListener。但我们把它放在 SuperAoh 进程里跑，宿主必须先接管
 *   GLSurfaceView 的生命周期，再反射转发给游戏实例，才能同时控制加载遮罩、
 *   错误捕获和"启动完成后跳编辑器"。
 *
 * 关键点：create() 只在第一次 render() 时调用一次，之后每帧转发 render()。
 * 这与 libGDX 的常规顺序不同（常规是先 create 后 resize 再 render），
 * 因为我们要等 GL context 真正可用。
 */
public final class GameBridge implements ApplicationListener {

    public interface Listener {
        void onFirstFrameRendered();

        void onStartupComplete();

        void onError(String stage, Throwable error);
    }

    private volatile Object game;
    private volatile boolean engineCreated;
    private volatile boolean startupComplete;
    private volatile String lastError;

    private int pendingWidth;
    private int pendingHeight;

    private Method mCreate;
    private Method mResize;
    private Method mRender;
    private Method mPause;
    private Method mResume;
    private Method mDispose;

    private Listener listener;

    public void attach(Object gameInstance, Listener listener) {
        this.game = gameInstance;
        this.listener = listener;
        bindMethods(gameInstance.getClass());
    }

    private void bindMethods(Class<?> type) {
        mCreate = find(type, "create");
        mResize = find(type, "resize", int.class, int.class);
        mRender = find(type, "render");
        mPause = find(type, "pause");
        mResume = find(type, "resume");
        mDispose = find(type, "dispose");
    }

    private static Method find(Class<?> type, String name, Class<?>... params) {
        try {
            Method m = type.getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public String getLastError() {
        return lastError;
    }

    public boolean isEngineCreated() {
        return engineCreated;
    }

    public boolean isStartupComplete() {
        return startupComplete;
    }

    public void markStartupComplete() {
        startupComplete = true;
    }

    // ---------------- ApplicationListener ----------------

    @Override
    public void create() {
        // 宿主 GL 已就绪，真正的 create 推迟到首帧
    }

    @Override
    public void resize(int width, int height) {
        pendingWidth = width;
        pendingHeight = height;
        if (!engineCreated) {
            return;
        }
        try {
            if (mResize != null) {
                mResize.invoke(game, width, height);
            }
        } catch (Throwable t) {
            report("engine.resize", t);
        }
    }

    @Override
    public void render() {
        if (game == null) {
            return;
        }
        if (!engineCreated) {
            engineCreated = true;
            try {
                if (mCreate != null) {
                    mCreate.invoke(game);
                }
                if (pendingWidth > 0 && pendingHeight > 0 && mResize != null) {
                    mResize.invoke(game, pendingWidth, pendingHeight);
                }
                if (listener != null) {
                    listener.onFirstFrameRendered();
                }
            } catch (Throwable t) {
                report("engine.create", t);
            }
            return;
        }
        try {
            if (mRender != null) {
                mRender.invoke(game);
            }
            if (!startupComplete && listener != null && StartupProbe.isReady()) {
                startupComplete = true;
                listener.onStartupComplete();
            }
        } catch (Throwable t) {
            report("engine.render", t);
        }
    }

    @Override
    public void pause() {
        invoke(mPause, "engine.pause");
    }

    @Override
    public void resume() {
        invoke(mResume, "engine.resume");
    }

    @Override
    public void dispose() {
        invoke(mDispose, "engine.dispose");
        game = null;
    }

    private void invoke(Method m, String stage) {
        if (m == null || game == null || !engineCreated) {
            return;
        }
        try {
            m.invoke(game);
        } catch (Throwable t) {
            report(stage, t);
        }
    }

    private void report(String stage, Throwable t) {
        if (lastError == null) {
            lastError = stage + ": " + t;
        }
        if (listener != null) {
            listener.onError(stage, t);
        }
    }

    // ---------------- 启动完成探测 ----------------

    /**
     * 游戏自己的加载流程：AoCGame.create() 之后先建 Menu_InitGame（viewID==0），
     * 逐帧加载资源，加载完才切到别的菜单。因此 viewID != 0 才算真正可用。
     * 此时 CFG.game / CFG.map / CFG.fontMain 必然已初始化，编辑器才敢进。
     */
    static final class StartupProbe {

        static boolean isReady() {
            try {
                Class<?> cfg = GameRuntime.cfgClass();
                if (cfg == null) {
                    return false;
                }
                Object game = GameRuntime.staticField(cfg, "game");
                Object map = GameRuntime.staticField(cfg, "map");
                Object font = GameRuntime.staticField(cfg, "fontMain");
                Object menuManager = GameRuntime.staticField(cfg, "menuManager");
                if (game == null || map == null || font == null || menuManager == null) {
                    return false;
                }
                Integer viewId = GameRuntime.intField(menuManager, "viewID");
                if (viewId == null) {
                    return false;
                }
                return viewId != 0;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private StartupProbe() {
        }
    }

    static Field declaredFieldOrNull(Object instance, String name) {
        try {
            Field f = instance.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable ignored) {
            return null;
        }
    }
}