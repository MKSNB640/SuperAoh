package com.superaoh.core.toolchain;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.superaoh.core.sandbox.UbuntuSandbox;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 反编译工具链部署器（apktool / baksmali / smali / jadx）。
 *
 * 为什么不做成「直接 import 的库」：
 *   这四个都是 JVM 程序，运行需要完整的 java 运行时、大量堆内存和文件系统访问。
 *   把它们塞进 App 进程跑，会和应用自己的 ART 运行时抢内存，且 apktool/jadx
 *   大量依赖 NIO 与临时文件，在应用沙箱里容易被 SELinux 拦住。
 *   本项目已有经过真机验收的 Ubuntu proot 沙箱，把工具链放进沙箱是最稳的路径。
 *
 * 部署位置（真机实测结论，踩过两个坑）：
 *
 *   坑 1：/data/local/tmp/superaoh/tools
 *     看似最合理（shell 域、可执行），但 UbuntuSandbox.buildCommand()
 *     根本没把 /data/local/tmp 绑进 proot。
 *     实测：java -jar /data/local/tmp/apktool.jar -> Unable to access jarfile
 *
 *   坑 2：appCtx.getFilesDir()（/data/user/0/<pkg>/files）
 *     buildCommand 里确实有 -b <filesDir>，但 proot 是用 **Shizuku 的
 *     shell 身份(uid 2000)** 拉起的，shell 无权 realpath 这个目录。
 *     实测：proot warning: can't sanitize binding ".../files": Permission denied
 *     绑定失败 → proot 内看不到该目录。
 *
 *   正确位置：App 的外部私有目录
 *     /sdcard/Android/data/<pkg>/files/toolchain
 *     它被 buildCommand 作为共享存储的一部分绑进来（/sdcard），
 *     任何 Android 版本都不需要额外权限，App 和 shell 都能读写。
 *     实测：java -jar /sdcard/Android/data/<pkg>/files/toolchain/apktool.jar
 *           --version -> 2.11.1  ✅
 *
 *   jar 只需被 java 读取，不需要可执行位，所以共享存储的 noexec 不影响。
 *
 * 一次性成本：首次调用把 161MB 资产从 assets 复制到该目录。
 * 之后按版本号判断，已部署就直接复用，不再复制。
 *
 * 版本号写死在这里：换了工具版本必须同时改 TOOLCHAIN_VERSION，否则老版本会被复用。
 */
public final class ToolchainManager {

    private static final String TAG = "SuperAoh/Toolchain";
/**
     * 工具链版本。任何 jar 变更都要递增，触发重新部署。
     */
    private static final String TOOLCHAIN_VERSION = "1";

    /** 中转目录里的标记文件，记录已部署的版本。 */
    private static final String STAMP_FILE = ".toolchain-version";

    private final Context appCtx;
    private final UbuntuSandbox sandbox;
    public ToolchainManager(Context ctx, UbuntuSandbox sandbox) {
        this.appCtx = ctx.getApplicationContext();
        this.sandbox = sandbox;
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    /**
     * 工具链的存放目录 = proot 内可见的路径。
     *
     * 用外部私有目录 /sdcard/Android/data/<pkg>/files/toolchain：
     *   · 被 buildCommand 作为共享存储绑进 proot（proot 内是 /sdcard/...）
     *   · 不需要任何存储权限，任何 Android 版本都可读写
     *   · App 和 Shizuku 的 shell 身份都能访问
     *
     * 详见类注释里记录的两次实测失败。
     */
    private File stagingDir() {
        File ext = appCtx.getExternalFilesDir(null);
        if (ext == null) {
            // 极少数情况下外部私有目录不可用（如存储被卸载），
            // 退回内部目录。此时 proot 可能读不到，会在 preflight 里报出来。
            ext = appCtx.getFilesDir();
        }
        File f = new File(ext, "toolchain");
        ensure(f);
        return f;
    }

    /**
     * 工具链在 proot 内的路径。
     *
     * proot 把共享存储绑成 /sdcard，所以要把宿主路径换算成 proot 视角：
     *   /storage/emulated/0/Android/data/<pkg>/files/toolchain
     *   -> /sdcard/Android/data/<pkg>/files/toolchain
     *
     * 两种写法在 proot 里都能用（/storage/emulated/0 也被单独绑过），
     * 这里统一用 /sdcard，因为它是 buildCommand 里显式指定的别名。
     */
    private String runtimeToolsPath() {
        File dir = stagingDir();
        String abs = dir.getAbsolutePath();
        File shared = sandbox.sharedStorageRoot();
        if (shared != null) {
            String root = shared.getAbsolutePath();
            if (abs.startsWith(root)) {
                return "/sdcard" + abs.substring(root.length());
            }
        }
        // 退回内部目录时，proot 内路径就取 /data/data/<pkg>/files/...
        // （这条绑定在 shell 身份下可能失效，preflight 会给出提示）
        return abs;
    }

    private File stampFile() {
        return new File(stagingDir(), STAMP_FILE);
    }

    /** 是否正在部署（供状态描述用，不参与就绪判断）。 */
    private volatile boolean deploying;

    /** 是否已部署。只做轻量检查，不打文件哈希。 */
    public boolean isReady() {
        File stamp = stampFile();
        if (!stamp.isFile()) return false;
        String version = readText(stamp).trim();
        if (!TOOLCHAIN_VERSION.equals(version)) return false;
        // 标记对得上还不够：用户可能手动删过目录。
        // 这里必须用宿主路径检查（stagingDir），不是 runtimeToolsPath()——
        // 后者是 proot 视角的 /sdcard/...，在 App 进程里不存在这个路径。
        File dir = stagingDir();
        return new File(dir, "apktool.jar").isFile()
                && new File(dir, "baksmali.jar").isFile()
                && new File(dir, "smali.jar").isFile()
                && new File(dir, "jadx/jadx.jar").isFile();
    }

    /** 给模型看的一句话状态。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("工具链版本: ").append(TOOLCHAIN_VERSION).append('\n');
        sb.append("已部署: ").append(isReady() ? "是" : "否").append('\n');
        // 体积统计用宿主路径（App 进程能读到的真实文件）
        File dir = stagingDir();
        sb.append("apktool: ").append(sizeText(new File(dir, "apktool.jar"))).append('\n');
        sb.append("baksmali: ").append(sizeText(new File(dir, "baksmali.jar"))).append('\n');
        sb.append("smali: ").append(sizeText(new File(dir, "smali.jar"))).append('\n');
        sb.append("jadx: ").append(sizeText(new File(dir, "jadx/jadx.jar"))).append('\n');
        sb.append("宿主目录: ").append(dir.getAbsolutePath()).append('\n');
        sb.append("沙箱内路径: ").append(runtimeToolsPath());
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 部署
    // ------------------------------------------------------------------

    public interface Progress {
        void onStage(String stage, int percent);
    }

    /**
     * 部署工具链。阻塞，必须放后台线程。
     *
     * 幂等：已部署且版本一致时立即返回。
     */
    public void deploy(Progress cb) throws Exception {
        if (isReady()) {
            if (cb != null) cb.onStage("工具链已就绪", 100);
            return;
        }
        deploying = true;
        try {
            deployInternal(cb);
        } finally {
            deploying = false;
        }
    }

    /**
     * 给模型看的「为什么现在不能用」。
     *
     * 区分「正在部署」和「没部署」很重要：启动后有几秒钟窗口期，
     * 如果这时模型问状态、我们答「尚未部署」并建议它调 toolchain_deploy，
     * 模型会白跑一次部署（虽然幂等，但会浪费时间并可能误导用户）。
     */
    public String pendingReason() {
        if (isReady()) return null;
        if (deploying) return "工具链正在后台部署，请稍等几秒再调用。";
        if (!sandbox.isInstalled()) {
            return "Ubuntu 沙箱尚未安装。请先到「设置 → 沙箱」安装，首次约需 1~3 分钟。";
        }
        return "工具链尚未部署，会自动完成，请稍后重试。";
    }

    private void deployInternal(Progress cb) throws Exception {
        if (!sandbox.isInstalled()) {
            throw new IllegalStateException("Ubuntu 沙箱尚未安装，无法部署工具链。请先在设置里安装沙箱。");
        }

        // 直接写 App 私有目录即可：它是 proot 内唯一被绑定进来的自有目录。
        // 不需要 Shizuku —— jar 只要被 java 读到就能跑，不要求可执行位。
        File dir = stagingDir();

        if (cb != null) cb.onStage("复制 apktool（24MB）…", 5);
        copyAsset("toolchain/apktool.jar", new File(dir, "apktool.jar"));

        if (cb != null) cb.onStage("复制 baksmali（4MB）…", 30);
        copyAsset("toolchain/baksmali.jar", new File(dir, "baksmali.jar"));

        if (cb != null) cb.onStage("复制 smali（5MB）…", 45);
        copyAsset("toolchain/smali.jar", new File(dir, "smali.jar"));

        if (cb != null) cb.onStage("复制 jadx（127MB，耗时较长）…", 60);
        File jadxDir = new File(dir, "jadx");
        ensure(jadxDir);
        copyAsset("toolchain/jadx/jadx.jar", new File(jadxDir, "jadx.jar"));

        // ---- 第二步：写版本戳 ----
        //
        // 版本戳只在文件真的落盘之后才写，否则会出现「标记说好了、实际文件不在」，
        // 之后每次调用都直接报错而不会重试部署。
        if (cb != null) cb.onStage("写入版本标记", 92);
        writeText(stampFile(), TOOLCHAIN_VERSION);

        if (cb != null) cb.onStage("工具链部署完成", 100);
        Log.i(TAG, "工具链部署完成: " + runtimeToolsPath());
    }

    // ------------------------------------------------------------------
    // 调用
    // ------------------------------------------------------------------

    /**
     * 构造一条在沙箱里调用指定工具的完整命令。
     *
     * 所有参数都已由调用方做过白名单校验；这里只负责拼装，不信任模型输入。
     *
     * @param tool    apktool / baksmali / smali / jadx
     * @param args    已经过校验的 CLI 参数
     * @param heapMb  JVM 最大堆，按任务规模给
     */
    public String buildCommand(String tool, java.util.List<String> args, int heapMb) {
        StringBuilder sb = new StringBuilder();
        String base = runtimeToolsPath();

        // 编码三件套，缺一不可（真机实测，这是中文路径能不能用的分界线）：
        //
        //   工作区叫 /sdcard/SuperAoh项目，中文段在 bash 里完好
        //   （echo 能打出来），但 JVM 打开文件时会变成 SuperAoh??????，
        //   报 "Can't find file"。原因是 JVM 默认的 sun.jnu.encoding
        //   不是 UTF-8，文件名在 native 层就被替换成 '?' 了。
        //
        //   修好后同一个 baksmali 调用从 0 个产出变成 17572 个 smali。
        //
        //   LANG/LC_ALL 也要给，否则 glibc 的 locale 仍是 C/POSIX。
        sb.append("LANG=C.UTF-8 LC_ALL=C.UTF-8 ");
        sb.append("java -Xmx").append(heapMb).append('m');
        sb.append(" -Dsun.jnu.encoding=UTF-8 -Dfile.encoding=UTF-8");
        if ("jadx".equals(tool)) {
            sb.append(" -cp ").append(quote(base + "/jadx/jadx.jar"))
                    .append(" jadx.cli.JadxCLI");
        } else {
            sb.append(" -jar ").append(quote(base + "/" + tool + ".jar"));
        }
        for (String a : args) {
            sb.append(' ').append(quote(toSandboxPath(a)));
        }
        return sb.toString();
    }

    /**
     * 把宿主路径改写成沙箱内路径。
     *
     * 为什么必须做这一步（真机实测）：
     *
     *   直接传 /storage/emulated/0/SuperAoh项目/xxx 给 proot 里的 java，
     *   中文段会变成 "SuperAoh??????"，报 Can't find file。
     *   proot 对 -b /storage/emulated/0 这种绑定的路径转义有缺陷，
     *   而经 /sdcard 别名访问同样的目录完全正常：
     *     ls /sdcard/SuperAoh项目/...  ✅
     *     ls /storage/emulated/0/SuperAoh项目/...  ❌（乱码）
     *
     *   所以规则很简单：只要在共享存储根之下，统一改写成 /sdcard 开头。
     *   非路径参数（如 d / b / -o / --single-class）不含这个前缀，原样返回。
     */
    private String toSandboxPath(String arg) {
        if (arg == null || arg.isEmpty()) return arg;
        File shared = sandbox.sharedStorageRoot();
        if (shared == null) return arg;
        String root = shared.getAbsolutePath();          // /storage/emulated/0
        if (arg.equals(root)) return "/sdcard";
        if (arg.startsWith(root + "/")) {
            return "/sdcard" + arg.substring(root.length());
        }
        return arg;
    }

    /** 工具链在 proot 里的实际位置，用于给模型提示。 */
    public String runtimePath() {
        return runtimeToolsPath();
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /** 单引号转义。模型传进来的路径可能带空格或中文，必须整体包起来。 */
    public static String quote(String raw) {
        if (raw == null) return "''";
        return "'" + raw.replace("'", "'\\''") + "'";
    }

    private void copyAsset(String assetPath, File dest) throws Exception {
        if (dest.isFile() && dest.length() > 0) return;
        File parent = dest.getParentFile();
        if (parent != null) ensure(parent);
        AssetManager am = appCtx.getAssets();
        try (InputStream in = am.open(assetPath);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        }
    }

    private static void ensure(File dir) {
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IllegalStateException("无法创建目录: " + dir.getAbsolutePath());
        }
    }

    private static String readText(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) Math.min(f.length(), 4096)];
            int offset = 0;
            int n;
            while (offset < data.length && (n = in.read(data, offset, data.length - offset)) > 0) {
                offset += n;
            }
            return new String(data, 0, offset, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static void writeText(File f, String text) throws Exception {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes("UTF-8"));
            out.flush();
        }
    }

    private static String sizeText(File f) {
        if (f == null || !f.isFile()) return "缺失";
        long n = f.length();
        if (n < 1024) return n + "B";
        if (n < 1024 * 1024) return (n / 1024) + "KB";
        return (n / (1024 * 1024)) + "MB";
    }
}
