package com.superaoh.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Markwon 渲染验证页（仅调试用，不对外暴露入口）。
 * 用一组真实 Markdown 覆盖常见语法，验证渲染不崩、不吞字。
 */
public class MarkwonTestActivity extends Activity {

    private static final String SAMPLE =
            "# 一级标题\n" +
            "\n" +
            "这是一段**粗体**、*斜体*、`行内代码`的普通段落。\n" +
            "\n" +
            "## 列表\n" +
            "- 第一项\n" +
            "- 第二项\n" +
            "  - 嵌套项\n" +
            "\n" +
            "1. 有序一\n" +
            "2. 有序二\n" +
            "\n" +
            "> 这是一段引用文字。\n" +
            "\n" +
            "[链接文本](https://example.com)\n" +
            "\n" +
            "```java\n" +
            "public class A {\n" +
            "    void f() { System.out.println(\"hi\"); }\n" +
            "}\n" +
            "```\n" +
            "\n" +
            "---\n" +
            "\n" +
            "表格语法不支持也没关系，重点是不崩。\n";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(40, 40, 40, 40);

        TextView tv = new TextView(this);
        tv.setTextSize(15f);
        tv.setTextColor(0xFFEDEFFF);

        try {
            io.noties.markwon.Markwon md = io.noties.markwon.Markwon.create(this);
            tv.setText(md.toMarkdown(SAMPLE));
        } catch (Throwable t) {
            tv.setText("MARKWON_FAIL: " + t);
        }

        col.addView(tv);
        sv.addView(col);
        setContentView(sv);
    }
}