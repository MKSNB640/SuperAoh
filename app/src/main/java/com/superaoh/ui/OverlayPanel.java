package com.superaoh.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 覆盖在游戏画面之上的面板（日志 / 调试共用）。
 *
 * 设计要点：
 *   · 半透明背景，不全遮住游戏，方便边玩边看
 *   · 内容可滚动、可选中复制
 *   · 关闭后立即销毁，不常驻内存
 */
public final class OverlayPanel {

    public interface Provider {
        /** 每次刷新时重新取文本。 */
        String content();
    }

    private final Activity activity;
    private final FrameLayout host;

    private LinearLayout panel;
    private TextView body;
    private TextView titleView;
    private WebView chatWebView;
    private View chatRoot;
    private ViewGroup chatParent;

    public OverlayPanel(Activity activity, FrameLayout host) {
        this.activity = activity;
        this.host = host;
    }

    public boolean isShowing() {
        return panel != null && panel.getVisibility() == View.VISIBLE;
    }

    public boolean isChatShowing() {
        return panel != null && panel.getVisibility() == View.VISIBLE
                && chatRoot != null && chatRoot.getParent() == panel;
    }

    public void show(String title, Provider provider, final Runnable onClose) {
        if (chatRoot != null) {
            // 从聊天切回文本面板时，先从宿主移除旧面板，避免旧 WebView
            // 继续留在 GL 视图层级下方；WebView 本身保留，供下次重新挂载。
            if (chatRoot.getParent() instanceof ViewGroup) {
                ((ViewGroup) chatRoot.getParent()).removeView(chatRoot);
            }
            if (panel != null && panel.getParent() instanceof ViewGroup) {
                ((ViewGroup) panel.getParent()).removeView(panel);
            }
            panel = null;
            chatRoot = null;
            chatWebView = null;
            body = null;
            titleView = null;
            build(title);
        } else if (panel == null) {
            build(title);
        }
        reload = provider;
        titleView.setText(title);
        body.setText(provider == null ? "" : provider.content());
        panel.setVisibility(View.VISIBLE);
        host.bringChildToFront(panel);
    }

    public void refresh(Provider provider) {
        if (panel != null && provider != null) {
            body.setText(provider.content());
        }
    }

    public void hide() {
        if (panel != null) {
            panel.setVisibility(View.GONE);
        }
    }

    public void showChat(String title, View chatView) {
        if (panel == null) {
            build(title);
        }
        titleView.setText(title);
        if (chatRoot != null && chatRoot.getParent() instanceof ViewGroup) {
            ((ViewGroup) chatRoot.getParent()).removeView(chatRoot);
        }
        if (chatWebView != null && chatWebView != chatView) {
            chatWebView.stopLoading();
            chatWebView.loadUrl("about:blank");
            chatWebView.destroy();
            chatWebView = null;
        }
        chatRoot = chatView;
        if (chatView instanceof WebView) chatWebView = (WebView) chatView;
        panel.removeAllViews();
        panel.setPadding(0, 0, 0, 0);
        panel.setBackgroundColor(0xFF0B1019);
        panel.addView(chatView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panel.setVisibility(View.VISIBLE);
        host.bringChildToFront(panel);
    }

    private void build(String title) {
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xF00A0D14);
        int pad = dp(12);
        panel.setPadding(pad, pad, pad, pad);
        panel.setClickable(true); // 吃掉触摸，避免误触到底下的游戏

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(0xFFE8EAFF);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(titleView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button refreshBtn = new Button(activity);
        refreshBtn.setText("刷新");
        refreshBtn.setOnClickListener(v -> {
            if (reload != null) {
                body.setText(reload.content());
            }
        });
        bar.addView(refreshBtn);

        Button closeBtn = new Button(activity);
        closeBtn.setText("关闭");
        closeBtn.setOnClickListener(v -> hide());
        bar.addView(closeBtn);

        panel.addView(bar);

        ScrollView scroll = new ScrollView(activity);
        body = new TextView(activity);
        body.setTextColor(0xFFA9B4D0);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(0, dp(8), 0, dp(8));
        scroll.addView(body);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(56), dp(8), dp(8), dp(8));
        host.addView(panel, lp);
        panel.setVisibility(View.GONE);
    }

    /** 供「刷新」按钮复用最近一次的取数逻辑。 */
    private Provider reload;

    public void setReloadProvider(Provider p) {
        this.reload = p;
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}