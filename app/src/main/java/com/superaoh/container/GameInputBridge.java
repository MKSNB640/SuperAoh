package com.superaoh.container;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Narrow game input bridge. AI taps are normalized, single-shot, and disabled by default. */
public final class GameInputBridge {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Activity activity;
    private static volatile View gameView;
    private static volatile boolean controlsEnabled;

    private GameInputBridge() {}

    public static void bind(Activity host, View view) {
        activity = host;
        gameView = view;
        controlsEnabled = false;
    }

    public static void unbind(Activity host) {
        if (activity == host) {
            controlsEnabled = false;
            activity = null;
            gameView = null;
        }
    }

    public static boolean setControlsEnabled(boolean enabled) {
        if (activity == null || gameView == null) enabled = false;
        controlsEnabled = enabled;
        return controlsEnabled;
    }

    public static boolean isControlsEnabled() {
        return controlsEnabled && activity != null && gameView != null;
    }

    public static String status() {
        return "ready=" + GameRuntime.isGameReady()
                + ", viewID=" + GameRuntime.currentViewId()
                + ", editor=" + GameRuntime.isEditorVisible()
                + ", aiControls=" + isControlsEnabled();
    }

    public static boolean tap(float x, float y) {
        Activity host = activity;
        View target = gameView;
        if (!isControlsEnabled() || host == null || target == null || !GameRuntime.isGameReady()) return false;
        if (Float.isNaN(x) || Float.isNaN(y) || x < 0f || x > 1f || y < 0f || y > 1f) return false;
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        MAIN.post(() -> {
            try {
                if (activity != host || gameView != target || !isControlsEnabled()) return;
                int width = target.getWidth();
                int height = target.getHeight();
                if (width <= 0 || height <= 0 || !target.isAttachedToWindow()) return;
                float px = Math.max(1f, Math.min(width - 1f, x * width));
                float py = Math.max(1f, Math.min(height - 1f, y * height));
                long down = System.currentTimeMillis();
                MotionEvent downEvent = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, px, py, 0);
                try {
                    ok[0] = target.dispatchTouchEvent(downEvent);
                } finally {
                    downEvent.recycle();
                }
                MAIN.postDelayed(() -> {
                    try {
                        long up = System.currentTimeMillis();
                        MotionEvent upEvent = MotionEvent.obtain(down, up, MotionEvent.ACTION_UP, px, py, 0);
                        try {
                            boolean upOk = target.dispatchTouchEvent(upEvent);
                            ok[0] = ok[0] || upOk;
                        } finally {
                            upEvent.recycle();
                        }
                    } catch (Throwable ignored) {
                        ok[0] = false;
                    } finally {
                        latch.countDown();
                    }
                }, 70);
            } catch (Throwable ignored) {
                ok[0] = false;
                latch.countDown();
            }
        });
        try {
            return latch.await(2, TimeUnit.SECONDS) && ok[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static boolean openEditor() {
        if (activity == null || gameView == null || !GameRuntime.isGameReady()) return false;
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        MAIN.post(() -> {
            try {
                ok[0] = GameRuntime.openEditor();
            } finally {
                latch.countDown();
            }
        });
        try {
            return latch.await(2, TimeUnit.SECONDS) && ok[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
