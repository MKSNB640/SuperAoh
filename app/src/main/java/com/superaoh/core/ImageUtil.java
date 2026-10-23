package com.superaoh.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 图片处理：从相册读取、缩放、压缩、转 data URL。
 *
 * 为什么要自己缩放：
 *   手机拍的照片动辄 4000×3000、5MB 以上。直接 base64 上传会：
 *     · 撑爆请求体（部分网关限制 10MB）
 *     · 消耗巨量 token（一张原图可能上千 token）
 *   缩到长边 1280 再压成 JPEG 85%，通常在 150~300KB，
 *   视觉模型识别截图/界面完全够用。
 *
 * 为什么用 data URL 而不是先上传：
 *   OpenAI 兼容接口支持 content 里直接放 data URL，
 *   免去文件服务器，对单机 App 是最简方案。
 */
public final class ImageUtil {

    /** 长边上限。1280 对游戏截图/UI 截图足够。 */
    private static final int MAX_EDGE = 1280;

    /** JPEG 质量。85 在体积和清晰度间比较平衡。 */
    private static final int JPEG_QUALITY = 85;

    private ImageUtil() {
    }

    /** 一张已处理的图片。 */
    public static final class Prepared {
        /** data:image/jpeg;base64,... */
        public final String dataUrl;
        /** 保存到本地的副本，用于聊天记录回显。 */
        public final File file;
        /** 处理后的字节数。 */
        public final int bytes;

        Prepared(String dataUrl, File file, int bytes) {
            this.dataUrl = dataUrl;
            this.file = file;
            this.bytes = bytes;
        }

        public String sizeText() {
            if (bytes < 1024) {
                return bytes + " B";
            }
            if (bytes < 1024 * 1024) {
                return (bytes / 1024) + " KB";
            }
            return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
        }
    }

    /**
     * 从 Uri 读取图片并处理。必须在后台线程调用。
     *
     * @param ctx      上下文
     * @param uri      相册返回的 Uri
     * @param destDir  处理后图片的保存目录
     * @param destName 文件名（不含扩展名）
     */
    public static Prepared prepare(Context ctx, Uri uri, File destDir, String destName) {
        InputStream in = null;
        try {
            // 第一步：只读边界，算出合适的采样率，避免把原图整个解码进内存
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            BitmapFactory.decodeStream(in, null, probe);
            closeQuietly(in);

            int w = probe.outWidth;
            int h = probe.outHeight;
            if (w <= 0 || h <= 0) {
                return null;
            }

            int sample = 1;
            int longEdge = Math.max(w, h);
            while (longEdge / sample > MAX_EDGE * 2) {
                sample *= 2;
            }

            // 第二步：真正解码
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            Bitmap bmp = BitmapFactory.decodeStream(in, null, opt);
            closeQuietly(in);
            if (bmp == null) {
                return null;
            }

            // 第三步：精确缩放到长边 <= MAX_EDGE
            Bitmap scaled = scaleDown(bmp, MAX_EDGE);
            if (scaled != bmp) {
                bmp.recycle();
            }

            // 第四步：压成 JPEG
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos);
            byte[] jpeg = bos.toByteArray();
            scaled.recycle();

            // 第五步：落盘 + 转 data URL
            Workspace.ensureDir(destDir);
            File out = new File(destDir, destName + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(jpeg);
                fos.flush();
            }

            String b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP);
            String dataUrl = "data:image/jpeg;base64," + b64;
            return new Prepared(dataUrl, out, jpeg.length);
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    /** 等比缩放到长边不超过 max。已经够小就原样返回。 */
    private static Bitmap scaleDown(Bitmap src, int max) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longEdge = Math.max(w, h);
        if (longEdge <= max) {
            return src;
        }
        float ratio = (float) max / longEdge;
        int nw = Math.max(1, Math.round(w * ratio));
        int nh = Math.max(1, Math.round(h * ratio));
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    /** 从本地文件重建 data URL（用于历史消息回显后再次发送）。 */
    public static String fileToDataUrl(File f) {
        if (f == null || !f.exists()) {
            return null;
        }
        try {
            byte[] bytes = new byte[(int) f.length()];
            try (InputStream in = new java.io.FileInputStream(f)) {
                int read = 0;
                while (read < bytes.length) {
                    int n = in.read(bytes, read, bytes.length - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
            }
            String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            return "data:image/jpeg;base64," + b64;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }
}