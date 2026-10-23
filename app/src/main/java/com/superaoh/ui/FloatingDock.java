package com.superaoh.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 游戏容器内的圆形悬浮球 + 功能菜单。
 *
 * 为什么必须有它：
 *   容器 Activity 是全屏沉浸式（config.useImmersiveMode = true），系统导航栏被隐藏；
 *   同时游戏自己消费了触摸与返回手势。结果是一旦进入游戏画面，
 *   用户既回不到主页，也调不出任何 SuperAoh 的功能。
 *   所以在 GLSurfaceView 之上叠一个圆形悬浮球，点击展开功能菜单。
 *
 * 为什么不用 WindowManager 系统悬浮窗：
 *   那需要申请 SYSTEM_ALERT_WINDOW（用户还得手动去设置里开），
 *   而且窗口泄漏/被系统回收都会带来额外麻烦。
 *   本球只是 Activity 内部的普通子 View，直接叠在 SurfaceView 之上即可生效
 *   （已被实测验证：加载面板能完整盖住游戏画面，说明同层叠加有效）。
 *
 * 交互：
 *   · 单击      展开 / 收起菜单
 *   · 按住拖动  移动位置，松手后自动吸附到左右边缘
 *   · 菜单项    返回主页 / 打开编辑器 / 查看日志 / 在线调试 / 退出游戏
 */
public final class FloatingDock {

    /** 宿主回调，由 Activity 实现。 */
    public interface Callbacks {
        /** 返回 SuperAoh 主页（保留游戏进程）。 */
        void onHome();

        /** 打开游戏内置编辑器。 */
        void onEditor();

        /** 查看运行日志。 */
        void onLog();

        /** 在线调试面板。 */
        void onDebug();

        /** 唤起 AI 助手。 */
        void onAi();

        /** 彻底退出游戏。 */
        void onExit();
    }

    private static final int BALL_SIZE_DP = 46;
    private static final int MARGIN_DP = 10;

    private final Activity activity;
    private final FrameLayout host;
    private final Callbacks callbacks;

    private final TextView ball;
    private final LinearLayout menu;

    private boolean menuOpen;

    private float downRawX;
    private float downRawY;
    private int startLeft;
    private int startTop;
    private boolean dragging;

    public FloatingDock(Activity activity, FrameLayout host, Callbacks callbacks) {
        this.activity = activity;
        this.host = host;
        this.callbacks = callbacks;

        // ---- 圆形悬浮球 ----
        ball = new TextView(activity);
        ball.setText("SA");
        ball.setTextColor(Color.WHITE);
        ball.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        ball.setGravity(Gravity.CENTER);
        ball.setTypeface(Typeface.DEFAULT_BOLD);
        ball.setBackground(ballBackground());
        ball.setAlpha(0.85f);
        ball.setElevation(dp(6));

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(BALL_SIZE_DP), dp(BALL_SIZE_DP));
        blp.gravity = Gravity.TOP | Gravity.START;
        blp.leftMargin = dp(MARGIN_DP);
        blp.topMargin = dp(40);
        host.addView(ball, blp);

        // ---- 展开菜单 ----
        menu = buildMenu();
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.gravity = Gravity.TOP | Gravity.START;
        mlp.leftMargin = dp(MARGIN_DP);
        mlp.topMargin = dp(40);
        host.addView(menu, mlp);
        menu.setVisibility(View.GONE);

        installTouch();

        // 初始停在右上角，避开游戏左上角的主菜单按钮
        host.post(() -> {
            int left = Math.max(dp(MARGIN_DP), host.getWidth() - ball.getWidth() - dp(12));
            placeBall(left, dp(40));
        });
    }

    // ---------------- 外观 ----------------

    private GradientDrawable ballBackground() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xD91E2230);
        g.setStroke(dp(2), 0xFF5B8CFF);
        return g;
    }

    private GradientDrawable menuBackground() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(14));
        g.setColor(0xF210131A);
        g.setStroke(dp(1), 0x665B8CFF);
        return g;
    }

    private LinearLayout buildMenu() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(menuBackground());
        box.setPadding(dp(6), dp(6), dp(6), dp(6));
        box.setElevation(dp(10));

        box.addView(item("返回主页", 0xFFB9C2DE, () -> callbacks.onHome()));
        box.addView(item("打开编辑器", 0xFFB9C2DE, () -> callbacks.onEditor()));
        box.addView(item("查看日志", 0xFFB9C2DE, () -> callbacks.onLog()));
        box.addView(item("在线调试", 0xFFB9C2DE, () -> callbacks.onDebug()));
        box.addView(item("AI 助手", 0xFF8FA9FF, () -> callbacks.onAi()));
        box.addView(item("退出游戏", 0xFFFF8A80, () -> callbacks.onExit()));
        return box;
    }

    private TextView item(String text, int color, final Runnable action) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        tv.setMinWidth(dp(112));
        tv.setClickable(true);
        tv.setOnClickListener(v -> {
            hideMenu();
            if (action != null) {
                action.run();
            }
        });
        return tv;
    }

    // ---------------- 交互 ----------------

    private void installTouch() {
        ball.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) ball.getLayoutParams();
                    startLeft = lp.leftMargin;
                    startTop = lp.topMargin;
                    dragging = false;
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    int dx = (int) (e.getRawX() - downRawX);
                    int dy = (int) (e.getRawY() - downRawY);
                    if (!dragging && (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6))) {
                        dragging = true;
                        hideMenu();
                    }
                    if (dragging) {
                        placeBall(startLeft + dx, startTop + dy);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (dragging) {
                        dragging = false;
                        snapToEdge();
                    } else {
                        toggleMenu();
                    }
                    return true;
                }
                default:
                    return false;
            }
        });
    }

    private void placeBall(int left, int top) {
        int maxLeft = Math.max(0, host.getWidth() - ball.getWidth());
        int maxTop = Math.max(0, host.getHeight() - ball.getHeight());
        int l = Math.min(Math.max(0, left), maxLeft);
        int t = Math.min(Math.max(0, top), maxTop);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) ball.getLayoutParams();
        lp.leftMargin = l;
        lp.topMargin = t;
        ball.setLayoutParams(lp);
        if (menuOpen) {
            positionMenu();
        }
    }

    /** 松手后吸附到最近的左/右边缘，避免球停在画面正中间挡视线。 */
    private void snapToEdge() {
        if (host.getWidth() <= 0) {
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) ball.getLayoutParams();
        int centerX = lp.leftMargin + ball.getWidth() / 2;
        int target = centerX < host.getWidth() / 2
                ? dp(MARGIN_DP)
                : host.getWidth() - ball.getWidth() - dp(MARGIN_DP);
        placeBall(target, lp.topMargin);
    }

    private void toggleMenu() {
        if (menuOpen) {
            hideMenu();
            return;
        }
        menuOpen = true;
        menu.setVisibility(View.VISIBLE);
        positionMenu();
        ball.setAlpha(1f);
    }

    /** 菜单永远贴着球展开，并且不超出屏幕边界。 */
    private void positionMenu() {
        menu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int mw = menu.getMeasuredWidth();
        int mh = menu.getMeasuredHeight();
        if (mw <= 0 || mh <= 0) {
            return;
        }

        int hostW = host.getWidth();
        int hostH = host.getHeight();
        FrameLayout.LayoutParams blp = (FrameLayout.LayoutParams) ball.getLayoutParams();

        int centerX = blp.leftMargin + ball.getWidth() / 2;
        int left;
        if (centerX < hostW / 2) {
            left = blp.leftMargin + ball.getWidth() + dp(6);
        } else {
            left = blp.leftMargin - mw - dp(6);
        }
        left = Math.max(dp(4), Math.min(left, hostW - mw - dp(4)));

        int top = blp.topMargin;
        top = Math.max(dp(4), Math.min(top, hostH - mh - dp(4)));

        FrameLayout.LayoutParams mlp = (FrameLayout.LayoutParams) menu.getLayoutParams();
        mlp.leftMargin = left;
        mlp.topMargin = top;
        menu.setLayoutParams(mlp);
    }

    // ---------------- 对外 ----------------

    public void openMenu() {
        if (!menuOpen) {
            toggleMenu();
        }
    }

    public void hideMenu() {
        menuOpen = false;
        menu.setVisibility(View.GONE);
        ball.setAlpha(0.85f);
    }

    public boolean isMenuOpen() {
        return menuOpen;
    }

    public void show() {
        ball.setVisibility(View.VISIBLE);
    }

    public void hide() {
        hideMenu();
        ball.setVisibility(View.GONE);
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}