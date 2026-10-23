package com.superaoh.ui.compose

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Text
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * 本地图片缩略图。
 *
 * 为什么不用 Coil：
 *   1. 这是唯一需要加载图片的地方，为了一个缩略图引入整套 Coil 不划算
 *   2. 我们的图片都是本地文件（ImageUtil 已经压缩到 1280px），直接解码即可
 *   3. 大量图片时 LazyRow 会复用，靠 remember(path) 自动释放
 *
 * 解码做了降采样：按目标尺寸 inSampleSize，避免把 1280px 原图整张读进内存。
 */
@Composable
fun ImageThumb(path: String, size: Dp) {
    val targetPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        size.roundToPx()
    }
    val bmp: ImageBitmap? = remember(path, targetPx) {
        decodeSampled(path, targetPx)
    }

    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF1B2130)),
        )
    } else {
        // 解码失败/文件缺失：显示占位，避免布局跳动
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF1B2130)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "图片\n丢失",
                color = Color(0xFF5A6274),
                fontSize = 9.sp,
                textAlign = TextAlign.Center,
                lineHeight = 11.sp,
            )
        }
    }
}

/**
 * 按目标尺寸降采样解码。
 *
 * @return 解码失败返回 null（文件不存在、格式不支持、OOM）
 */
private fun decodeSampled(path: String, targetPx: Int): ImageBitmap? {
    return try {
        val f = File(path)
        if (!f.exists() || f.length() == 0L) return null

        // 第一趟只读边界，算 inSampleSize
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        var sample = 1
        val minSide = minOf(w, h)
        // 保证降采样后仍不小于目标尺寸，避免糊
        while (minSide / (sample * 2) >= targetPx) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
        bmp.asImageBitmap()
    } catch (t: Throwable) {
        // OOM 也算在这里：宁可显示占位，不要让整个列表崩
        null
    }
}