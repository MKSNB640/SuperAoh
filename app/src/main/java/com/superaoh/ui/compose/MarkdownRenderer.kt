package com.superaoh.ui.compose

import android.content.Context
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme

// ---------------------------------------------------------------------------
// Compose 侧的 Markdown 渲染
//
// 思路：复用旧版的 Markwon（纯 Java、无 WebView，APK 里已有）把 Markdown
// 解析成 Android Spanned，再把 Spanned 上的 Span 翻译成 Compose 的
// AnnotatedString + SpanStyle。
//
// 为什么不自己写 Markdown 解析器：
//   模型输出五花八门（嵌套列表、粗斜体混排、行内代码、表格、链接），
//   自己写必然有大量边界 bug。Markwon 是成熟库，旧版已验证可用。
//
// 为什么不把 Spanned 塞进 AndroidView 里的 TextView：
//   在 Compose 里嵌 AndroidView 会带来测量、滚动、文本选中等一堆问题。
//   转成 AnnotatedString 后就是纯 Compose 文本，行为可预测。
// ---------------------------------------------------------------------------

/**
 * Markdown 渲染器。
 *
 * @param context 任意 Context，内部持有 applicationContext
 * @param density 屏幕 scaledDensity，用于把 Android 的 px 字号换算成 sp。
 *                由调用方从 LocalDensity 取，因为非 Composable 函数里
 *                不能访问 CompositionLocal。
 */
class MarkdownRenderer(context: Context, private val density: Float) {

    private val appCtx = context.applicationContext

    private val markwon: Markwon = Markwon.builder(appCtx)
        .usePlugin(object : io.noties.markwon.AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                // 这些值会写进 Spanned 的 Span 里，下面翻译时读出来用。
                val d = appCtx.resources.displayMetrics.density
                builder
                    .codeTextColor(CODE_TEXT)
                    .codeBlockTextColor(CODE_TEXT)
                    .codeBlockBackgroundColor(CODE_BG)
                    .codeBlockMargin((d * 6).toInt())
                    .bulletWidth((d * 10).toInt())
                    .linkColor(LINK)
                    .headingBreakHeight(0)
            }
        })
        .build()

    /**
     * 把 Markdown 渲染成 AnnotatedString。
     *
     * @param md           原始 Markdown
     * @param textColor    正文颜色
     * @param mutedColor   次要文字颜色（引用块）
     * @param linkColor    链接颜色（Markwon 已按主题色染色，这里保留参数备用）
     * @param baseFontSize 基准字号，标题按 Markwon 给的倍率放大
     */
    fun render(
        md: String,
        textColor: Color,
        mutedColor: Color,
        @Suppress("UNUSED_PARAMETER") linkColor: Color,
        baseFontSize: TextUnit = 14.sp,
    ): AnnotatedString {
        if (md.isEmpty()) return AnnotatedString("")

        val normalized = normalizeMarkdown(md)

        val spanned: Spanned = try {
            markwon.toMarkdown(normalized) as Spanned
        } catch (t: Throwable) {
            // 极端畸形 Markdown 解析失败：退化成纯文本，绝不崩
            return AnnotatedString(normalized)
        }

        // baseFontSize 换算成 px，后面和 Span 的倍率相乘
        val basePx = baseFontSize.value * density

        return buildAnnotatedString {
            // 第一步：整段先铺正文色。后加的 Span 会覆盖它，所以必须先铺。
            pushStyle(SpanStyle(color = textColor))
            append(spanned.toString())
            pop()

            // 第二步：把 Android 的 Span 逐个翻译过来
            val spans = spanned.getSpans(0, spanned.length, Any::class.java)
            for (span in spans) {
                val start = spanned.getSpanStart(span)
                val end = spanned.getSpanEnd(span)
                if (start < 0 || end <= start || end > length) continue

                val style = translate(span, mutedColor, basePx) ?: continue
                addStyle(style, start, end)
            }
        }
    }

    /**
     * 单个 Span 的翻译。
     *
     * 只处理对可读性有影响的类型；其余忽略：
     *   BulletSpan / LeadingMarginSpan —— Compose 的 Text 不做段落缩进，
     *     列表符号 Markwon 已经写进文本里了
     *   ImageSpan —— 模型几乎不在 Markdown 里插图
     *   ClickableSpan —— 链接点击后续再接，目前只染色
     */
    private fun translate(span: Any, mutedColor: Color, basePx: Float): SpanStyle? {
        return when (span) {
            // ---- 字形 ----
            is StyleSpan -> when (span.style) {
                Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                Typeface.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                Typeface.BOLD_ITALIC -> SpanStyle(
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic,
                )
                else -> null
            }

            is UnderlineSpan -> SpanStyle(textDecoration = TextDecoration.Underline)

            is StrikethroughSpan -> SpanStyle(textDecoration = TextDecoration.LineThrough)

            // ---- 颜色 ----
            is ForegroundColorSpan -> SpanStyle(color = Color(span.foregroundColor))

            is BackgroundColorSpan -> SpanStyle(background = Color(span.backgroundColor))

            // ---- 字号 ----
            // Markwon 用 RelativeSizeSpan 放大标题（h1=2.0, h2=1.6 ...）
            is RelativeSizeSpan -> SpanStyle(
                fontSize = (basePx * span.sizeChange / density).sp
            )

            // 行内代码用的是 AbsoluteSizeSpan(px)
            is AbsoluteSizeSpan -> SpanStyle(
                fontSize = (span.size / density).sp
            )

            // ---- 字体 ----
            is TypefaceSpan -> {
                val family = span.family
                if (family != null && family.contains("mono", ignoreCase = true)) {
                    SpanStyle(fontFamily = FontFamily.Monospace)
                } else {
                    null
                }
            }

            // ---- 引用块 ----
            // Compose 没有 QuoteSpan 的等价物，用变色近似
            is QuoteSpan -> SpanStyle(color = mutedColor)

            else -> null
        }
    }
}

// ---------------------------------------------------------------------------
// 常量与预处理
// ---------------------------------------------------------------------------

private val CODE_TEXT = 0xFFE8EBF5.toInt()
private val CODE_BG = 0xFF11151E.toInt()
private val LINK = 0xFF5B8CFF.toInt()

/**
 * Markdown 预处理。
 *
 * 沿用旧版 AiChatActivity.normalizeMarkdown 的两条规则，都是线上踩出来的：
 *   1. 模型经常写 ```java 但不闭合 → 整段代码被当普通文本吞掉
 *   2. 连续 3+ 空行会把气泡撑得很高
 */
internal fun normalizeMarkdown(md: String): String {
    var s = md.replace("\r\n", "\n")

    var fences = 0
    var idx = 0
    while (true) {
        val f = s.indexOf("```", idx)
        if (f < 0) break
        fences++
        idx = f + 3
    }
    if (fences % 2 == 1) {
        s += "\n```"
    }

    return s.replace(Regex("\n{3,}"), "\n\n")
}