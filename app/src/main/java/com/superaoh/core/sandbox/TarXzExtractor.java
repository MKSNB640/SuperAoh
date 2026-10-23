package com.superaoh.core.sandbox;

import android.util.Log;

import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

// ---------------------------------------------------------------------------
// 纯 Java 的 tar.xz 解压器
//
// 为什么不用 /system/bin/tar：
//   toybox 的 tar 虽然声称支持 -J（xz），但它是「exec 外部 xz 程序」实现的，
//   而 Android 系统里根本没有 xz 这个可执行文件 —— 实测报
//   "tar: exec xz: No such file or directory"，退出码 127。
//   所以这条路在 Android 上走不通，必须自己在 App 里解 xz。
//
// 为什么用流式而不是先解到内存：
//   rootfs 解压后约 300MB，任何「先读进 byte[] 再处理」的做法都会 OOM。
//   这里全程流式：XZInputStream -> TarReader -> 逐条写盘，内存占用只有几十 KB。
//
// 为什么不用 commons-compress 的 TarArchiveInputStream：
//   它要额外 677KB 依赖，且对 tar 的 GNU 长文件名、PAX 扩展头的处理不如
//   自己写透明 —— 我们明确知道这个 tar 是哪来的（Ubuntu 官方 rootfs 用
//   GNU tar 打包），只需要支持三种条目：普通文件、目录、符号链接。
//   自己实现 200 行，行为完全可控。
// ---------------------------------------------------------------------------

public final class TarXzExtractor {

    private static final String TAG = "TarXzExtractor";

    /** 解压进度回调。 */
    public interface Progress {
        /**
         * @param entries 已处理条目数
         * @param bytes   已写出字节数
         * @param current 当前正在写的文件相对路径
         */
        void onEntry(int entries, long bytes, String current);
    }

    private TarXzExtractor() {
    }

    /**
     * 解压 tar.xz 到目标目录。
     *
     * @param src xz 文件
     * @param dst 目标目录（必须已存在）
     * @return 处理的条目数
     */
    public static int extract(File src, File dst, Progress cb) throws IOException {
        if (!dst.isDirectory() && !dst.mkdirs()) {
            throw new IOException("无法创建目标目录: " + dst);
        }

        InputStream fin = null;
        XZInputStream xz = null;
        try {
            fin = new BufferedInputStream(new java.io.FileInputStream(src), 256 * 1024);
            xz = new XZInputStream(fin);
            return readTar(xz, dst, cb);
        } finally {
            closeQuietly(xz);
            closeQuietly(fin);
        }
    }

    // ------------------------------------------------------------------
    // tar 解析
    // ------------------------------------------------------------------

    /** tar 头块大小。 */
    private static final int BLOCK = 512;

    /** 最大允许的文件长度，防脏数据导致的狂写。 */
    private static final long MAX_ENTRY = 2L * 1024 * 1024 * 1024; // 2GB

    private static int readTar(InputStream in, File dstRoot, Progress cb) throws IOException {
        byte[] header = new byte[BLOCK];
        int entries = 0;
        long written = 0;
        // GNU 长文件名暂存
        String longName = null;
        // PAX 扩展头里的 path
        String paxPath = null;

        String rootPath = dstRoot.getAbsolutePath();

        while (true) {
            if (!readFully(in, header)) {
                break;
            }
            // 连续两个全零块表示归档结束
            if (isZeroBlock(header)) {
                break;
            }

            String name = parseName(header);
            long size = parseSize(header);
            char type = (char) (header[156] & 0xFF);

            if (size < 0 || size > MAX_ENTRY) {
                throw new IOException("异常条目长度: " + size + " (" + name + ")");
            }

            // 类型分发
            switch (type) {
                case 'L': { // GNU long name
                    byte[] buf = new byte[(int) size];
                    if (!readFully(in, buf)) throw new IOException("长文件名块截断");
                    longName = cstr(buf);
                    skipPadding(in, size);
                    continue;
                }
                case 'x': { // PAX 扩展头
                    byte[] buf = new byte[(int) size];
                    if (!readFully(in, buf)) throw new IOException("PAX 头截断");
                    paxPath = parsePaxPath(buf);
                    skipPadding(in, size);
                    continue;
                }
                case 'g': { // PAX 全局头，跳过
                    skipExactly(in, size + padding(size));
                    continue;
                }
                default:
                    break;
            }

            // 组装最终路径
            String finalName = name;
            if (longName != null) {
                finalName = longName;
                longName = null;
            }
            if (paxPath != null) {
                finalName = paxPath;
                paxPath = null;
            }

            // 归一化并做越界校验
            finalName = normalize(finalName);
            if (finalName == null) {
                // 危险路径，跳过内容
                skipExactly(in, size + padding(size));
                continue;
            }

            File out = new File(dstRoot, finalName);
            // 双保险：确保落点仍在 dstRoot 内
            if (!out.getAbsolutePath().startsWith(rootPath)) {
                skipExactly(in, size + padding(size));
                continue;
            }

            entries++;

            switch (type) {
                case '5': { // 目录
                    if (!out.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        out.mkdirs();
                    }
                    applyMode(out, header);
                    break;
                }
                case '2': { // 符号链接
                    String target = parseLinkName(header);
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    }
                    // 已存在则先删（tar 允许覆盖）
                    if (out.exists() || isSymlink(out)) {
                        //noinspection ResultOfMethodCallIgnored
                        out.delete();
                    }
                    try {
                        createSymlink(target, out);
                    } catch (Throwable t) {
                        // 部分文件系统不支持符号链接，退化成空文件并记录 —— 不要中断整个解压
                        Log.w(TAG, "符号链接失败 " + finalName + " -> " + target
                                + " : " + t.getMessage());
                        writeEmpty(out);
                    }
                    break;
                }
                case '1': { // 硬链接：在 Android 上退化为复制源文件
                    String target = parseLinkName(header);
                    File srcFile = new File(dstRoot, normalize(target));
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    }
                    if (srcFile.isFile() && !out.exists()) {
                        try {
                            copyFile(srcFile, out);
                        } catch (Throwable ignored) {
                            writeEmpty(out);
                        }
                    }
                    break;
                }
                case '0':
                case '\0': { // 普通文件（旧 tar 用 NUL 表示普通文件）
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    }
                    written += writeFile(in, out, size);
                    skipPadding(in, size);
                    applyMode(out, header);
                    break;
                }
                default: {
                    // 其他类型（设备节点、FIFO 等）直接跳过内容
                    // Android 无权限 mknod，这些条目在 rootfs 里本来就很少
                    skipExactly(in, size + padding(size));
                    break;
                }
            }

            if (cb != null && (entries % 200 == 0)) {
                cb.onEntry(entries, written, finalName);
            }
        }

        if (cb != null) {
            cb.onEntry(entries, written, "");
        }
        Log.i(TAG, "解压完成: " + entries + " 条目, " + written + " 字节");
        return entries;
    }

    // ------------------------------------------------------------------
    // 头字段解析
    // ------------------------------------------------------------------

    /** 解析 name 字段（偏移 0，长度 100）。GNU 用 prefix(345,155) 拼长路径。 */
    private static String parseName(byte[] h) {
        String name = cstr(h, 0, 100);
        String prefix = cstr(h, 345, 155);
        if (!prefix.isEmpty()) {
            return prefix + "/" + name;
        }
        return name;
    }

    /** 解析 linkname 字段（偏移 157，长度 100）。 */
    private static String parseLinkName(byte[] h) {
        return cstr(h, 157, 100);
    }

    /**
     * 解析 size 字段（偏移 124，长度 12）。
     *
     * 常规是八进制 ASCII。但 GNU tar 对超大文件会用 base-256 编码
     * （首字节最高位为 1）。rootfs 里不会有大文件，但防御性处理一下。
     */
    private static long parseSize(byte[] h) {
        int off = 124;
        if ((h[off] & 0x80) != 0) {
            // base-256
            long v = h[off] & 0x7F;
            for (int i = off + 1; i < off + 12; i++) {
                v = (v << 8) | (h[i] & 0xFF);
            }
            return v;
        }
        return parseOctal(h, off, 12);
    }

    private static long parseOctal(byte[] h, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            int c = h[i] & 0xFF;
            if (c == 0 || c == ' ') continue;
            if (c < '0' || c > '7') break;
            v = v * 8 + (c - '0');
        }
        return v;
    }

    /** mode 字段（偏移 100，长度 8）。 */
    private static void applyMode(File f, byte[] h) {
        long mode = parseOctal(h, 100, 8);
        if (mode == 0) return;
        try {
            boolean ownerExec = (mode & 0100) != 0;
            if (!f.isDirectory()) {
                // noinspection ResultOfMethodCallIgnored
                f.setReadable(true, true);
                // noinspection ResultOfMethodCallIgnored
                f.setWritable(true, true);
                if (ownerExec) {
                    // noinspection ResultOfMethodCallIgnored
                    f.setExecutable(true, true);
                }
            }
        } catch (Throwable ignored) {
            // 权限设置失败不影响文件内容正确性
        }
    }

    /** PAX 扩展头里的 path= 记录。格式是 "长度 key=value\n"。 */
    private static String parsePaxPath(byte[] buf) {
        String s = new String(buf, java.nio.charset.Charset.forName("UTF-8"));
        int i = 0;
        while (i < s.length()) {
            int sp = s.indexOf(' ', i);
            if (sp < 0) break;
            int len;
            try {
                len = Integer.parseInt(s.substring(i, sp).trim());
            } catch (Throwable t) {
                break;
            }
            if (len <= 0) break;
            int end = i + len;
            if (end > s.length()) break;
            String record = s.substring(sp + 1, end);
            if (record.startsWith("path=")) {
                return record.substring(5).replace("\n", "");
            }
            i = end;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 路径安全
    // ------------------------------------------------------------------

    /**
     * 归一化相对路径。
     *
     * 拒绝绝对路径和任何 ".." 逃逸 —— tar 里的路径是外部数据，不校验的话
     * 一个恶意 rootfs 就能往 App 私有目录外写文件。
     *
     * @return null 表示该条目应跳过
     */
    private static String normalize(String raw) {
        if (raw == null) return null;
        String p = raw.replace('\\', '/').trim();
        while (p.startsWith("./")) p = p.substring(2);
        if (p.startsWith("/")) return null;

        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) continue;
            if ("..".equals(seg)) {
                if (parts.isEmpty()) return null;
                parts.remove(parts.size() - 1);
            } else {
                parts.add(seg);
            }
        }
        if (parts.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String seg : parts) {
            if (sb.length() > 0) sb.append('/');
            sb.append(seg);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 低层 IO
    // ------------------------------------------------------------------

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                return off != 0 ? false : false;
            }
            off += n;
        }
        return true;
    }

    private static boolean isZeroBlock(byte[] h) {
        for (byte b : h) {
            if (b != 0) return false;
        }
        return true;
    }

    private static String cstr(byte[] h, int off, int len) {
        int end = off;
        while (end < off + len && h[end] != 0) end++;
        try {
            return new String(h, off, end - off, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String cstr(byte[] b) {
        int end = 0;
        while (end < b.length && b[end] != 0) end++;
        try {
            return new String(b, 0, end, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 512 对齐的补白长度。 */
    private static int padding(long size) {
        int r = (int) (size % BLOCK);
        return r == 0 ? 0 : BLOCK - r;
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        int pad = padding(size);
        if (pad > 0) skipExactly(in, pad);
    }

    private static void skipExactly(InputStream in, long n) throws IOException {
        long left = n;
        byte[] buf = new byte[4096];
        while (left > 0) {
            int want = (int) Math.min(buf.length, left);
            int got = in.read(buf, 0, want);
            if (got < 0) return;
            left -= got;
        }
    }

    /** 写文件，返回写出的字节数。 */
    private static long writeFile(InputStream in, File out, long size) throws IOException {
        FileOutputStream o = null;
        long written = 0;
        try {
            o = new FileOutputStream(out);
            byte[] buf = new byte[64 * 1024];
            long left = size;
            while (left > 0) {
                int want = (int) Math.min(buf.length, left);
                int got = in.read(buf, 0, want);
                if (got < 0) throw new IOException("文件内容截断: " + out);
                o.write(buf, 0, got);
                left -= got;
                written += got;
            }
            o.flush();
        } finally {
            closeQuietly(o);
        }
        return written;
    }

    /** 检测符号链接（File.exists 对断链返回 false，需要单独判断）。 */
    private static boolean isSymlink(File f) {
        try {
            File parent = f.getParentFile();
            if (parent == null) return false;
            File canon = new File(parent.getCanonicalFile(), f.getName());
            return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 创建符号链接。
     *
     * Android 上没有 Java 层的 symlink API，只能走 os.symlink。
     * 用反射调用 android.system.Os，避免在编译期依赖 API 21+ 的类
     * （虽然 minSdk 是 24，反射写法在这里更稳妥，兼容不同 ROM）。
     */
    private static void createSymlink(String target, File link) throws Exception {
        try {
            Class<?> os = Class.forName("android.system.Os");
            java.lang.reflect.Method m = os.getMethod("symlink",
                    String.class, String.class);
            m.invoke(null, target, link.getAbsolutePath());
        } catch (Throwable t) {
            throw new IOException("symlink 不可用", t);
        }
    }

    private static void writeEmpty(File out) {
        FileOutputStream o = null;
        try {
            o = new FileOutputStream(out);
            o.flush();
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(o);
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        java.io.FileInputStream in = null;
        OutputStream out = null;
        try {
            in = new java.io.FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }
}