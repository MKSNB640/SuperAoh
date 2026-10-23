package com.superaoh.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 统一的视觉规范与控件工厂。
 *
 * SuperAoh 没有引入 AppCompat / Material，全部用原生 widget 手绘。
 * 这个类集中管理颜色、圆角、间距、字号，保证各页面风格一致，
 * 也避免每处重复写一长串 setBackgroundColor / setPadding。
 *
 * 配色思路：深色底 + 低饱和蓝紫作为强调色，保证在游戏截图旁边也不刺眼。
 */
public final class Ui {

    // ---------------- 颜色 ----------------
    public static final int BG = 0xFF0B0E14;
    public static final int SURFACE = 0xFF141926;
    public static final int SURFACE_2 = 0xFF1B2130;
    public static final int BORDER = 0xFF262E40;

    public static final int TEXT = 0xFFEDEFFF;
    public static final int TEXT_SUB = 0xFF9AA4C0;
    public static final int TEXT_DIM = 0xFF6C7695;

    public static final int ACCENT = 0xFF5B8CFF;
    public static final int ACCENT_DIM = 0xFF2C4470;
    public static final int OK = 0xFF62D68B;
    public static final int WARN = 0xFFFFC46B;
    public static final int DANGER = 0xFFFF8A80;

    public static final int BUBBLE_USER = 0xFF2A3E66;
    public static final int BUBBLE_AI = 0xFF1A2030;
    public static final int BUBBLE_TOOL = 0xFF15251F;

    private Ui() {
    }

    // ---------------- 尺寸 ----------------

    public static int dp(Context ctx, float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    public static int sp(Context ctx, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                v, ctx.getResources().getDisplayMetrics()));
    }

    // ---------------- 背景 ----------------

    public static GradientDrawable roundRect(int color, int radiusDp, Context ctx) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(dp(ctx, radiusDp));
        return g;
    }

    public static GradientDrawable roundRectStroke(int color, int radiusDp,
                                                   int strokeColor, int strokeDp,
                                                   Context ctx) {
        GradientDrawable g = roundRect(color, radiusDp, ctx);
        g.setStroke(dp(ctx, strokeDp), strokeColor);
        return g;
    }

    /** 只有一个角是圆的（用于聊天气泡）。 */
    public static GradientDrawable bubble(int color, Context ctx,
                                          boolean cornerTopLeft, boolean cornerTopRight,
                                          boolean cornerBottomLeft, boolean cornerBottomRight) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        int r = dp(ctx, 16);
        int small = dp(ctx, 4);
        g.setCornerRadii(new float[]{
                cornerTopLeft ? r : small, cornerTopLeft ? r : small,
                cornerTopRight ? r : small, cornerTopRight ? r : small,
                cornerBottomRight ? r : small, cornerBottomRight ? r : small,
                cornerBottomLeft ? r : small, cornerBottomLeft ? r : small
        });
        return g;
    }

    // ---------------- 文本 ----------------

    public static TextView text(Context ctx, String s, float sizeSp, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(s);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        return tv;
    }

    public static TextView title(Context ctx, String s) {
        TextView tv = text(ctx, s, 24f, TEXT);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    public static TextView subtitle(Context ctx, String s) {
        TextView tv = text(ctx, s, 12f, TEXT_DIM);
        return tv;
    }

    public static TextView mono(Context ctx, String s, float sizeSp, int color) {
        TextView tv = text(ctx, s, sizeSp, color);
        tv.setTypeface(Typeface.MONOSPACE);
        return tv;
    }

    // ---------------- 控件 ----------------

    /** 主按钮。 */
    public static Button primaryButton(Context ctx, String label) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14f);
        b.setTextColor(0xFFFFFFFF);
        b.setBackground(roundRect(ACCENT_DIM, 10, ctx));
        b.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10));
        return b;
    }

    /** 次要按钮（描边）。 */
    public static Button ghostButton(Context ctx, String label) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13f);
        b.setTextColor(TEXT_SUB);
        b.setBackground(roundRectStroke(SURFACE_2, 10, BORDER, 1, ctx));
        b.setPadding(dp(ctx, 14), dp(ctx, 9), dp(ctx, 14), dp(ctx, 9));
        return b;
    }

    /** 危险按钮。 */
    public static Button dangerButton(Context ctx, String label) {
        Button b = ghostButton(ctx, label);
        b.setTextColor(DANGER);
        return b;
    }

    /** 卡片容器。 */
    public static LinearLayout card(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundRectStroke(SURFACE, 14, BORDER, 1, ctx));
        int p = dp(ctx, 14);
        box.setPadding(p, p, p, p);
        return box;
    }

    /** 横向分隔线。 */
    public static View divider(Context ctx) {
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 1)));
        lp.topMargin = dp(ctx, 10);
        lp.bottomMargin = dp(ctx, 10);
        v.setLayoutParams(lp);
        v.setBackgroundColor(BORDER);
        return v;
    }

    /** 小圆角标签。 */
    public static TextView chip(Context ctx, String label, int color) {
        TextView tv = text(ctx, label, 10f, color);
        tv.setBackground(roundRect(withAlpha(color, 0x22), 6, ctx));
        tv.setPadding(dp(ctx, 7), dp(ctx, 3), dp(ctx, 7), dp(ctx, 3));
        return tv;
    }

    /** 给颜色加透明度。 */
    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** 一行水平排布，平均分宽。 */
    public static LinearLayout row(Context ctx, View... children) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        for (View c : children) {
            r.addView(c, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return r;
    }

    /** 带间距的垂直容器。 */
    public static LinearLayout column(Context ctx, int gapDp) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(0, 0, 0, 0);
        c.setTag(gapDp);
        return c;
    }

    public static LinearLayout.LayoutParams lpMatchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams lpMatchWrap(int topMarginDp, Context ctx) {
        LinearLayout.LayoutParams lp = lpMatchWrap();
        lp.topMargin = dp(ctx, topMarginDp);
        return lp;
    }
}