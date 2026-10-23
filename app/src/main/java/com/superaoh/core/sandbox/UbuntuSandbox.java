package com.superaoh.core.sandbox;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

// ---------------------------------------------------------------------------
// Ubuntu 沙箱（从 Operit 移植）
//
// 组成：
//   liboperit_proot.so     —— termux/proot 的 aarch64 编译产物（Android 原生二进制）
//   ubuntu-noble-*.tar.xz  —— Ubuntu 24.04 arm64 根文件系统，64MB
//
// 原理：
//   proot 用 ptrace 拦截 guest 程序的系统调用，把路径重写进 rootfs 目录，
//   从而在无 root 的应用进程里伪造出一个「完整的 Ubuntu」。因此它必须是
//   Android 原生 ELF（解释器 /system/bin/linker64），不能跑在 proot 里面。
//
// 关键约束（都是实测踩出来的）：
//   1. proot 二进制必须放到 app 私有目录再 chmod +x
//      —— assets 里的文件没有执行权限，且 /sdcard 是 noexec
//   2. rootfs 必须解压到 app 私有目录
//      —— /sdcard 是 fuse，不支持 symlink / 权限位，解压会丢一大半文件
//   3. 必须设置 PROOT_TMP_DIR 到私有目录
//      —— 默认走 /tmp，Android 上没有这个目录
//   4. 必须给 proot 传 --link2symlink
//      —— 否则硬链接创建失败（Android 内核限制）
// ---------------------------------------------------------------------------

public final class UbuntuSandbox {

    private static final String TAG = "UbuntuSandbox";

    /** rootfs 压缩包在 assets 里的名字。 */
    private static final String ROOTFS_ASSET = "ubuntu-noble-aarch64-pd-v4.18.0.tar.xz";

    /** 解压后根目录在 tar 里的顶层目录名。 */
    private static final String ROOTFS_TOP = "ubuntu-noble-aarch64";

    /** proot 在 nativeLibraryDir 里的文件名（Android 只允许 lib*.so 进那个目录）。 */
    private static final String PROOT_LIB = "libproot.so";

    private final Context appCtx;

    /**
     * 是否优先走 Shizuku。
     *
     * 默认 true：Shizuku 可用时沙箱才能真正跑起来（见 ShizukuShell 类注释）。
     * 留这个开关是为了排查问题时能强制走本地 exec 做对比。
     */
    private volatile boolean forceShizuku = true;

    public UbuntuSandbox(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    /** 关掉/打开 Shizuku 通道（仅调试用）。 */
    public void setForceShizuku(boolean v) {
        this.forceShizuku = v;
    }

    /** 当前实际走的是不是 Shizuku 通道。 */
    public boolean viaShizuku() {
        return useShizuku();
    }

    // ------------------------------------------------------------------
    // 路径
    // ------------------------------------------------------------------

    /** 沙箱根目录：/data/data/<pkg>/files/sandbox */
    public File home() {
        File f = new File(appCtx.getFilesDir(), "sandbox");
        ensure(f);
        return f;
    }

    /**
     * proot 可执行文件路径。
     *
     * 关键：必须放在 ApplicationInfo.nativeLibraryDir（即 APK 的 lib/arm64-v8a/），
     * 不能放在 app 私有目录。
     *
     * 为什么（这是 Android 10+ 的硬限制，实测踩出来的）：
     *   1. SELinux 把应用进程划进 untrusted_app 域，该域只被授予对
     *      /data/app/x/lib/x 下文件的 execute 权限。往私有目录 files/ 里放
     *      一个二进制再 chmod 755，exec 时内核仍返回 EACCES ——
     *      报错正是 "Cannot run program ...: error=13, Permission denied"。
     *   2. 即使绕过 SELinux，Android 10+ 的 W^X 策略也禁止从可写目录
     *      执行代码，防止运行时下载可执行文件。
     *
     * 这也解释了 Operit 为什么把 proot 命名为 liboperit_proot.so：
     *   PackageManager 只允许 lib*.so 形式的文件进入 nativeLibraryDir，
     *   改名是唯一能把它塞进那个可执行目录的办法。
     *   我们沿用同样的做法，叫 libproot.so。
     *
     * 前提条件：AndroidManifest 必须声明 android:extractNativeLibs="true"，
     *   否则系统不把这文件解压到磁盘，nativeLibraryDir 里会找不到它。
     */
    public File prootBin() {
        return new File(appCtx.getApplicationInfo().nativeLibraryDir, PROOT_LIB);
    }

    /** proot 是否已就位（只检查文件存在，不检查可执行）。 */
    public boolean hasProot() {
        return prootBin().isFile();
    }

    /** rootfs 实际挂载点：<home>/rootfs */
    public File rootfs() {
        return new File(home(), "rootfs");
    }

    // ------------------------------------------------------------------
    // rootfs 的两处落点（这是整个沙箱能不能跑起来的关键）
    //
    // 实测结论（一加 ColorOS / Android 15 / SDK 35）：
    //
    //   A. App 自己的进程（untrusted_app 域）
    //      - 能写 /data/user/0/<pkg>/files/**
    //      - 但（连同 rootfs 里的 ELF）一律不能 execve：EACCES
    //      - 也不能写 /data/local/tmp（SELinux shell_data_file 拒绝写入）
    //
    //   B. Shizuku 起的 shell 进程（shell 域，uid 2000）
    //      - 能 execve /data/local/tmp/**（这里是唯一能执行任意二进制的可写区）
    //      - 完全读不到 /data/user/0/<pkg>/files/**（app_data_file + category，
    //        即便 chmod 777 也不行 —— 是 SELinux 级别，不是权限位问题）
    //
    // 两边唯一能交汇的地方，是「外部存储的私有目录」：
    //      /storage/emulated/0/Android/data/<pkg>/files/
    //      - App 可读写（ext_data_rw）
    //      - shell 也可读（实测 cp 成功）
    //      - 但它是 FUSE 且 noexec，不能在这里执行
    //
    // 所以流程必须是三段：
    //   1. App 把 rootfs 解压到 stagingRootfs()（外部私有目录）
    //   2. Shizuku 以 shell 身份把它 cp 到 runtimeRootfs()（/data/local/tmp）
    //   3. proot 从 runtimeRootfs() 启动，且自己也在 /data/local/tmp
    // ------------------------------------------------------------------

    /** shell 域（Shizuku）放运行期 rootfs 的根目录。 */
    public static final String RUNTIME_HOME = "/data/local/tmp/superaoh";

    /**
     * rootfs 的暂存处：外部私有目录。
     *
     * 放这里的唯一理由：这是 App 和 shell 都能访问的地方。
     * 不能在这里运行（noexec），它只作为「中转站」。
     */
    public File stagingRootfs() {
        File dir = appCtx.getExternalFilesDir(null);
        if (dir == null) dir = appCtx.getFilesDir();
        File f = new File(dir, "staging-rootfs");
        ensure(f);
        return f;
    }

    /** rootfs 的运行处：/data/local/tmp/superaoh/rootfs（shell 域，可执行）。 */
    public File runtimeRootfs() {
        return new File(RUNTIME_HOME, "rootfs");
    }

    /** proot 的运行副本：必须是 shell 域里可执行的文件。 */
    public File runtimeProot() {
        return new File(RUNTIME_HOME, "proot");
    }

    /**
     * PROOT_TMP_DIR，proot 放临时文件的地方。
     *
     * 必须在 shell 域（/data/local/tmp）：proot 会往这里写临时文件并 chmod，
     * 在 App 私有目录里既写不进 shell 的期望权限，也影响 proot 自身行为。
     */
    private String prootTmpPath() {
        return RUNTIME_HOME + "/tmp";
    }

    /** 本地临时目录（App 侧用，例如 /dev/shm 的挂载源）。 */
    private File prootTmp() {
        File f = new File(home(), "tmp");
        ensure(f);
        return f;
    }

    /** Ubuntu 里的 /root 映射到这里，方便放脚本。
     *
     * 返回的是「宿主可见的路径」还是「Ubuntu 内路径」取决于调用方，
     * 这里给宿主侧真实位置（运行期 rootfs 在 shell 域）。
     */
    public File ubuntuHome() {
        return new File(runtimeRootfs(), "root");
    }

    /**
     * 是否已安装。
     *
     * 不能用 App 进程直接检查 /data/local/tmp：这个目录属于 shell 域，
     * 在本机 Android 15 的 App SELinux 视图中 stat 会返回 false，即使 shell
     * 已经把 rootfs/proot 部署成功。安装阶段因此把事实写到外部私有目录，
     * 这里读取 shell 生成的报告，而不是误判 runtimeRootfs().isFile()。
     */
    public boolean isInstalled() {
        File stage = new File(getExternalFilesDirSafe(), "stage-report.txt");
        File proot = new File(getExternalFilesDirSafe(), "proot-report.txt");
        return hasProot()
                && readReportFlag(stage, "BASH_EXEC=1")
                && readReportFlag(proot, "PROOT_EXEC=1");
    }

    // ------------------------------------------------------------------
    // 安装
    // ------------------------------------------------------------------

    /** 安装进度回调。回调在调用线程（通常是后台线程）。 */
    public interface Progress {
        void onStage(String message, int percent);
    }

    /**
     * 安装沙箱。已安装则直接返回。
     *
     * 耗时：在 aarch64 手机上解压 64MB xz 大约 1~3 分钟，取决于存储速度。
     * 必须放后台线程调用。
     */
    public void install(Progress cb) throws IOException {
        if (isInstalled()) {
            if (cb != null) cb.onStage("沙箱已安装", 100);
            return;
        }

        // ---- 0. 前置检查：这条路必须靠 Shizuku ----
        //
        // 为什么强依赖（不是可选优化）：
        //   rootfs 里的 ELF 只有在 /data/local/tmp（shell 域）才能被 execve，
        //   App 自己的 untrusted_app 域执行不了（实测 EACCES，SELinux 无日志）。
        //   而 /data/local/tmp App 写不进去，只能借 Shizuku 的 shell 身份搬过去。
        if (!ShizukuShell.isReady()) {
            throw new IOException(
                    "需要先授予 Shizuku 权限。\n\n"
                    + "原因：Android 不允许普通应用执行自己目录里的程序，"
                    + "Ubuntu 的 proot 必须借 Shizuku 的 shell 身份运行。\n"
                    + "当前状态：" + ShizukuShell.stateText());
        }

        // ---- 1. 检查 proot ----
        if (cb != null) cb.onStage("检查 proot 运行时…", 2);
        if (!hasProot()) {
            throw new IOException("找不到 proot 运行时：" + prootBin().getAbsolutePath()
                    + "\n请确认 AndroidManifest 里 extractNativeLibs=\"true\"，"
                    + "且 lib/arm64-v8a/libproot.so 已打进 APK。");
        }

        // ---- 2. 准备 rootfs：不解压到 FUSE，只把 xz 转成 tar ----
        //
        // 为什么不在 App 侧解压成目录树：
        //   App 能写的两个位置都放不下一个合格的 Linux rootfs——
        //     a) /data/user/0/<pkg>/files/**  FUSE 之外，ext4，能存链接，
        //        但 shell 读不到（SELinux app_data_file + category）；
        //     b) 外部私有目录（getExternalFilesDir）FUSE，shell 能读，
        //        但 **FUSE 不支持符号链接**，/bin -> usr/bin 会退化成 0 字节普通文件，
        //        解出来的 rootfs 直接报废（实测 bin/lib/sbin 全是 size=0）。
        //
        //   唯一同时满足「App 可写 + shell 可读 + 能存链接」的位置不存在，
        //   所以只能让 App 交出一个 tar（tar 格式自身记录链接类型，不依赖文件系统），
        //   由 shell 侧在 ext4 上解压。
        File home = home();
        if (cb != null) cb.onStage("准备 Ubuntu 根文件系统（约 300MB）…", 4);

        File tarXz = new File(home, "rootfs.tar.xz");
        if (!tarXz.isFile() || tarXz.length() == 0) {
            copyAsset(ROOTFS_ASSET, tarXz);
        }

        // ---- 3. 交给 Shizuku 解压到 /data/local/tmp ----
        if (cb != null) cb.onStage("正在搬入可执行区（需要 Shizuku）…", 10);
        stageIntoRuntime(tarXz, cb);

        // ---- 4. 部署 proot ----
        stageProot(cb);

        if (cb != null) cb.onStage("安装完成", 100);
        Log.i(TAG, "sandbox installed at " + runtimeRootfs().getAbsolutePath());
    }

    /**
     * 把外部暂存的 rootfs 搬到 /data/local/tmp/superaoh/rootfs。
     *
     * 为什么必须走 tar，而不能直接 cp -a：
     *   staging 位于外部存储（FUSE）。FUSE 不支持符号链接，Linux 的 rootfs 里
     *   /bin -> usr/bin、/lib -> usr/lib 这些关键链接在 FUSE 上会退化成
     *   0 字节普通文件（实测：cp -a 之后 bin/lib/sbin 全是 size=0 的普通文件）。
     *   rootfs 一旦没有这些链接就彻底跑不起来（找不到 /bin/bash）。
     *
     *   所以流程改成：App 侧写一个 tar（tar 里保存的是链接类型，不受 FUSE 影响），
     *   shell 侧用 toybox tar 解压到 /data/local/tmp —— ext4 支持符号链接，解出来就是对的。
     */
    private void stageIntoRuntime(File tarXz, Progress cb) throws IOException {
        String home = RUNTIME_HOME;
        String dst = runtimeRootfs().getAbsolutePath();

        // ---- 1. App 把 xz 解成纯 tar（普通文件，FUSE 上不会丢信息） ----
        //
        // 设备上没有 xz 命令（toybox 不带），所以这步只能 App 做。
        // 但只解 xz、不解析 tar —— 链接/权限/属主留在 tar 里，由 shell 侧还原。
        File plainTar = new File(getExternalFilesDirSafe(), "rootfs-plain.tar");
        if (!plainTar.isFile() || plainTar.length() == 0) {
            if (cb != null) cb.onStage("解压压缩包（约 300MB）…", 12);
            xzToTar(tarXz, plainTar);
        } else {
            Log.i(TAG, "复用已有 plainTar: " + plainTar.length());
        }

        // ---- 2. shell 侧解 tar 到 /data/local/tmp ----
        if (cb != null) cb.onStage("搬入可执行区（需要 Shizuku）…", 55);
        //
        // 关于 tar 的报错（很重要，别被它误导）：
        //   toybox 的 tar 会拒绝两类条目，而且返回非零退出码：
        //     1) 指向解压目录之外的「绝对符号链接」，例如
        //        /etc/alternatives/awk -> /usr/bin/mawk
        //        报 "not under '<解压目录>'"
        //     2) 设备节点，例如 /dev/null、/dev/zero
        //        报 "Operation not permitted"（非 root 无法 mknod）
        //   这两类对运行 Ubuntu 都没有影响：
        //     - /dev 下的内容由 proot 用 -b /dev 绑宿主 /dev 覆盖
        //     - alternatives 之类的辅助链接缺失不影响 bash/env
        //   实测：带着这些报错的 rootfs 能正常 proot 进 Ubuntu 24.04
        //        （uid=0、apt-get、python3 都可用）。
        //   所以这里必须忽略 tar 的退出码，改用「bin/bash 是否可执行」判断成败。
        String cmd = "H='" + home + "'; D='" + dst + "'; "
                + "mkdir -p \"$H\" && cd \"$H\" || exit 2; "
                // App 进程看不到 /data/local/tmp 的 SELinux 视图；用 shell 自己的 -x 结果
                // 判断是否已有有效 rootfs，避免每次重试都重复解压 315MB tar。
                + "if [ ! -x \"$D/bin/bash\" ]; then "
                +   "tar -xf '" + plainTar.getAbsolutePath() + "' -C \"$H\" >/dev/null 2>&1; "
                +   "if [ -e \"$H/" + ROOTFS_TOP + "\" ]; then "
                +     "rm -rf \"$D\"; mv \"$H/" + ROOTFS_TOP + "\" \"$D\" || exit 4; "
                +   "fi; "
                + "fi; "
                + "chmod -R a+rX \"$D\" 2>/dev/null; "
                + "mkdir -p \"$H/tmp\" \"$H/shm\"; chmod 777 \"$H/tmp\" \"$H/shm\"; "
                // rootfs 内必需目录与文件（App 无写权限，只能这里做）
                + "mkdir -p \"$D/tmp\" \"$D/root\" \"$D/dev\" \"$D/proc\" \"$D/sys\" "
                +   "\"$D/sdcard\" \"$D/storage\"; "
                + "chmod 1777 \"$D/tmp\" 2>/dev/null; "
                + "printf 'nameserver 8.8.8.8\\nnameserver 1.1.1.1\\n' > \"$D/etc/resolv.conf\" 2>/dev/null; "
                + "echo superaoh > \"$D/etc/hostname\" 2>/dev/null; "
+ "echo STAGE_COPY_OK; "
                + "if [ -x \"$D/bin/bash\" ]; then echo BASH_OK; else echo BASH_MISSING; fi";

        // 结果同时写一份到「外部私有目录」，App 可以直接读文件判定成败。
        //
        // 为什么不用 Shizuku 的 stdout：实测 RemoteProcess 的流在部分 ROM 上
        // 读不到内容（out 恒为空），靠它判断成功与否会误判成失败。
        // 落文件是确定性的，不受 binder 管道行为影响。
        File report = new File(getExternalFilesDirSafe(), "stage-report.txt");
        String cmdWithReport = cmd
                + "; { echo \"STAGE_EXIT=$?\"; "
                + "if [ -x \"$D/bin/bash\" ]; then echo BASH_EXEC=1; else echo BASH_EXEC=0; fi; "
                + "} > '" + report.getAbsolutePath() + "' 2>&1";

        long t0 = System.currentTimeMillis();
        ShizukuShell.Result r = ShizukuShell.exec(cmdWithReport, 15 * 60 * 1000L);
        Log.i(TAG, "stageIntoRuntime exit=" + r.exitCode + " ms="
                + (System.currentTimeMillis() - t0) + " out=" + r.output);

        // 以「文件里的事实」为准，而不是 stdout
        boolean bashReady = new File(runtimeRootfs(), "bin/bash").exists()
                || readReportFlag(report, "BASH_EXEC=1");
        Log.i(TAG, "stage 判定: bashReady=" + bashReady
                + " report=" + readReport(report));

        if (!bashReady) {
            throw new IOException("把 rootfs 解压到可执行区失败（退出码 " + r.exitCode + "）：\n"
                    + r.output
                    + "\n--- 现场报告 ---\n" + readReport(report)
                    + "\n如果提示 Permission denied，请确认 Shizuku 服务在运行且已授权本应用。");
        }
    }

    /** 读 shell 侧写的报告文件（读不到就返回空串）。 */
    private String readReport(File f) {
        try {
            if (!f.isFile()) return "(无报告文件)";
            byte[] buf = new byte[(int) Math.min(f.length(), 4096)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int n = in.read(buf);
                return n <= 0 ? "(空)" : new String(buf, 0, n, "UTF-8").trim();
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return "(读取失败: " + t.getMessage() + ")";
        }
    }

    private boolean readReportFlag(File f, String flag) {
        return readReport(f).contains(flag);
    }

    /** 把 proot 也部署到 shell 域（与 rootfs 同目录，同样必须可执行）。 */
    private void stageProot(Progress cb) throws IOException {
        // ---- 3. proot 也要在 shell 域 ----
        if (cb != null) cb.onStage("部署 proot…", 90);
        String prootSrc = prootBin().getAbsolutePath();
        String prootDst = runtimeProot().getAbsolutePath();
        File prootReport = new File(getExternalFilesDirSafe(), "proot-report.txt");
        String cmd2 = "cp -f '" + prootSrc + "' '" + prootDst + "'; "
                + "chmod 755 '" + prootDst + "'; "
                + "{ '" + prootDst + "' --version 2>&1 | head -3; "
                + "echo \"PROOT_EXEC=$([ -x '" + prootDst + "' ] && echo 1 || echo 0)\"; "
                + "} > '" + prootReport.getAbsolutePath() + "' 2>&1; "
                + "echo PROOT_STAGED_OK";
        ShizukuShell.Result r2 = ShizukuShell.exec(cmd2, 120000);
        Log.i(TAG, "stage proot exit=" + r2.exitCode + " out=" + r2.output
                + " report=" + readReport(prootReport));

        // 同样以文件事实为准：proot 可执行即算成功
        boolean prootReady = runtimeProot().canExecute() && runtimeProot().length() > 0
                || readReportFlag(prootReport, "PROOT_EXEC=1");
        if (!prootReady) {
            throw new IOException("把 proot 搬进可执行区失败：\n" + r2.output
                    + "\n--- 现场报告 ---\n" + readReport(prootReport));
        }
    }

    /**
     * 把 tar.xz 解成纯 tar（不落 rootfs）。
     *
     * 用 XZInputStream 解压后原样写字节 —— 不做 tar 解析，
     * 这样链接/权限位/属主全部原封不动地留给 shell 侧的 tar 去处理。
     */
    private void xzToTar(File xz, File tar) throws IOException {
        java.io.InputStream in = null;
        java.io.OutputStream out = null;
        try {
            in = new org.tukaani.xz.XZInputStream(
                    new java.io.BufferedInputStream(new java.io.FileInputStream(xz), 1 << 16));
            out = new java.io.BufferedOutputStream(new java.io.FileOutputStream(tar), 1 << 16);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
            if (out != null) try { out.close(); } catch (Throwable ignored) { }
        }
        Log.i(TAG, "xzToTar -> " + tar.getAbsolutePath() + " (" + tar.length() + " bytes)");
    }

    /**
     * 解压后的修补。
     *
     * 这几步不做的话，进 Ubuntu 会发现：
     *   - /etc/resolv.conf 缺失 → DNS 全部失败（pip/apt/curl 都用不了）
     *   - /tmp 不存在或权限不对 → 很多程序启动就报错
     *   - 缺 /dev/null 等 → 重定向直接失败
     */
private void fixupRootfs(File root) {

        // resolv.conf
        File resolv = new File(root, "etc/resolv.conf");
        if (!resolv.exists()) {
            writeText(resolv, "nameserver 8.8.8.8\nnameserver 1.1.1.1\n");
        }

        // hostname
        File hostname = new File(root, "etc/hostname");
        if (!hostname.exists()) {
            writeText(hostname, "superaoh\n");
        }

        // tmp 目录
        File tmp = new File(root, "tmp");
        ensure(tmp);
        tmp.setReadable(true, false);
        tmp.setWritable(true, false);
        tmp.setExecutable(true, false);

        // root 家目录
        ensure(new File(root, "root"));

        // dev 下的基础节点。Android 不允许 mknod，proot 会自己伪造，
        // 这里只保证目录存在即可。
        ensure(new File(root, "dev"));
        ensure(new File(root, "proc"));
        ensure(new File(root, "sys"));

        // proot 的绑定目标必须真实存在，否则有的 proot 版本会直接跳过整个 -b。
        // 这个 rootfs 的 tar 里本来没有 sdcard / storage，第一次装完
        // 沙箱里 /sdcard 就是 "No such file or directory"，必须补上。
        // 权限给满：proot 内部是伪造 root，但落到宿主仍然是 app uid，
        // 靠目录自己的权限位决定能不能写。
        for (String p : new String[]{"sdcard", "storage"}) {
            File d = new File(root, p);
            ensure(d);
            //noinspection ResultOfMethodCallIgnored
            d.setReadable(true, false);
            d.setWritable(true, false);
            d.setExecutable(true, false);
        }
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    /**
     * 构造 proot 启动参数。
     *
     * 挂载约定（与 Operit 一致，便于用户的脚本直接复用）：
     *   /sdcard        -> 宿主机共享存储；拿不到权限时降级到 App 专属外部目录
     *   /storage       -> 宿主机 /storage（仅在共享存储可用时）
     *   /data/data/<pkg>/files -> 同路径（方便访问沙箱自身）
     *   /dev, /proc, /sys 直通（否则没有设备节点、看不到进程）
     *
     * @param command 要在 Ubuntu 里执行的命令
     */
    public String[] buildCommand(String command) {
        List<String> a = new ArrayList<>();

        // proot 与 rootfs 都必须位于 /data/local/tmp（shell 域）。
        // 在 App 私有目录里 execve 会被内核拒绝（EACCES），这是本机实测结论。
        a.add(runtimeProot().getAbsolutePath());
        a.add("--link2symlink");
        a.add("-0");                       // 伪造 root 身份（uid 0）
        a.add("-r"); a.add(runtimeRootfs().getAbsolutePath());

        // 设备节点与内核接口直通。不绑这三个，Ubuntu 里没有 /dev/null、
        // 看不到进程、也读不到 CPU 信息，很多程序启动就报错。
        a.add("-b"); a.add("/dev");
        a.add("-b"); a.add("/proc");
        a.add("-b"); a.add("/sys");

        // ---- /sdcard ----
        //
        // 优先绑真实的共享存储。但 Android 11+ 的分区存储 + Android 13+
        // READ_EXTERNAL_STORAGE 失效，使得「没拿到 MANAGE_EXTERNAL_STORAGE」
        // 时 proot 对 /storage/emulated/0 做 realpath 会被 FUSE 拒绝，报
        //   can't sanitize binding "/storage/emulated/0": Permission denied
        // 结果 /sdcard 变成空目录，AI 什么都看不到，还以为存储坏了。
        //
        // 所以这里探测一次：拿不到就降级绑 App 专属外部目录
        // （/sdcard/Android/data/<pkg>/files），那个目录任何 Android 版本都
        // 不需要权限，AI 至少还能读写工作区文件，不会整个 /sdcard 空掉。
        File shared = sharedStorageRoot();
        if (shared != null) {
            a.add("-b"); a.add(shared.getAbsolutePath() + ":/sdcard");
            // 同时把原始路径也挂上（Operit 就是这么干的：/sdcard 和
            // /storage/emulated/0 各绑一次），这样两个路径都能用。
            a.add("-b"); a.add(shared.getAbsolutePath());
        } else {
            File ext = appCtx.getExternalFilesDir(null);
            if (ext != null) {
                //noinspection ResultOfMethodCallIgnored
                ext.mkdirs();
                a.add("-b"); a.add(ext.getAbsolutePath() + ":/sdcard");
            }
        }

        // /dev 下的 fd 与标准流。
        //
        // 注意：不要绑 /proc/self/fd/0|1|2。
        // ProcessBuilder 起的子进程这三根 fd 是 pipe，proot 对它们做 sanitize
        // 会失败（No such file or directory），接着 execve 也跟着报
        // Permission denied。改成把 fd 目录整体透传即可。
        a.add("-b"); a.add("/dev/pts");
        a.add("-b"); a.add("/proc/self/fd:/dev/fd");
        // /dev/shm：Python multiprocessing、Chrome 等都要用共享内存
        // 源用运行期目录（shell 域），与 proot 自身位置一致
        a.add("-b"); a.add(RUNTIME_HOME + "/shm:/dev/shm");

        // 让 Ubuntu 里也能直接访问自己的沙箱目录，脚本可以用绝对路径
        a.add("-b"); a.add(appCtx.getFilesDir().getAbsolutePath());
        // 同一路径的 /data/data/<pkg> 别名（很多脚本按这个路径写死）
        a.add("-b");
        a.add(appCtx.getFilesDir().getAbsolutePath()
                + ":/data/data/" + appCtx.getPackageName() + "/files");

        a.add("-w"); a.add("/root");
        a.add("/usr/bin/env");
        a.add("-i");
        a.add("HOME=/root");
        a.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        a.add("TERM=xterm-256color");
        a.add("LANG=C.UTF-8");
        a.add("/bin/bash");
        a.add("-lc");
        a.add(command);

        return a.toArray(new String[0]);
    }

    /**
     * 取共享存储根目录；拿不到就返回 null。
     *
     * 判据是「真的能列目录」。isExternalStorageManager() 只反映权限声明，
     * 而部分 ROM 即使给了权限也可能因为 FUSE 异常读不到，所以最终以
     * list() 能否返回非 null 为准 —— 那正好对应 proot realpath 会不会失败。
     */
    public File sharedStorageRoot() {
        File root = new File("/storage/emulated/0");
        try {
            if (root.isDirectory() && root.list() != null) return root;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 是否已获得「所有文件访问」（MANAGE_EXTERNAL_STORAGE）。 */
    public boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                return android.os.Environment.isExternalStorageManager();
            } catch (Throwable t) {
                return false;
            }
        }
        // API 29 及以下：READ/WRITE 授予即可访问共享存储
        try {
            return appCtx.checkSelfPermission(
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * /sdcard 当前指向哪里，给 UI 和 sandbox_status 用。
     * 返回人类可读的一行说明。
     */
    public String sharedStorageState() {
        File shared = sharedStorageRoot();
        if (shared != null) return "真实共享存储（" + shared.getAbsolutePath() + "）";
        if (hasAllFilesAccess()) {
            return "已授权但仍读不到 /storage/emulated/0，/sdcard 已降级到 App 专属目录";
        }
        return "未授予「所有文件访问」，/sdcard 已降级到 App 专属目录";
    }

    /** 一次命令执行的结果。 */
    public static final class ExecResult {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final long millis;

        ExecResult(int code, String out, String err, long ms) {
            this.exitCode = code;
            this.stdout = out;
            this.stderr = err;
            this.millis = ms;
        }

        public boolean ok() {
            return exitCode == 0;
        }

        /** 合并输出，给模型看。 */
        public String combined() {
            StringBuilder sb = new StringBuilder();
            if (stdout != null && !stdout.isEmpty()) sb.append(stdout);
            if (stderr != null && !stderr.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("[stderr]\n").append(stderr);
            }
            if (sb.length() == 0) sb.append("(无输出)");
            if (exitCode != 0) {
                sb.append("\n[退出码 ").append(exitCode).append(']');
            }
            return sb.toString();
        }
    }

    /**
     * 在 Ubuntu 沙箱里执行命令。
     *
     * 这是同步阻塞调用，必须放后台线程。
     *
     * @param command  要执行的 shell 命令
     * @param timeoutMs 超时，超时会强杀 proot 进程树
     */
    public ExecResult exec(String command, long timeoutMs) throws IOException {
        if (!isInstalled()) {
            throw new IOException("沙箱尚未安装");
        }

        // ---- 优先走 Shizuku ----
        //
        // 原因见 ShizukuShell 的类注释：untrusted_app 域不允许执行应用私有目录
        // 里的二进制，proot 内部 execve(rootfs 里的 /usr/bin/env) 会直接
        // EACCES。拿到 shell 身份后这个限制消失，proot 才能真正跑起来。
        //
        // Shizuku 不可用时退回直接 exec（低版本 Android / 未装 Shizuku 的机器
        // 上可能可用，也可能报同样错，此时会给出明确提示）。
        if (useShizuku()) {
            return execViaShizuku(command, timeoutMs);
        }

        return execDirect(command, timeoutMs);
    }

    /** 是否走 Shizuku 通道。可被 setForceShizuku(false) 关掉，方便对比排查。 */
    private boolean useShizuku() {
        return forceShizuku && ShizukuShell.isReady();
    }

    /**
     * 通过 Shizuku(shell 身份) 执行。这是唯一能真正跑通 proot 的路径。
     *
     * 环境变量必须显式给全：Shizuku 的服务端不会帮我们准备 PROOT_TMP_DIR，
     * 而 proot 写 loader/临时文件要用它。给 /data/local/tmp 下的目录，
     * 保证是 shell 自己能 chmod 的地方。
     */
    private ExecResult execViaShizuku(String command, long timeoutMs) throws IOException {
        String[] argv = buildCommand(command);
        long t0 = System.currentTimeMillis();

        // 先把 proot 需要的运行期目录准备好（tmp/shm），由 shell 建，
        // 免得第一次执行时 proot 自己 mkdir 失败。
        String prep = "mkdir -p '" + RUNTIME_HOME + "/tmp' '" + RUNTIME_HOME + "/shm' "
                + "&& chmod 777 '" + RUNTIME_HOME + "/tmp' '" + RUNTIME_HOME + "/shm'";
        ShizukuShell.exec(prep, 20000);

        ShizukuShell.Result r = ShizukuShell.execArray(
                argv, RUNTIME_HOME, timeoutMs,
                new String[]{
                        "PROOT_TMP_DIR=" + prootTmpPath(),
                        "HOME=/root",
                        "TERM=xterm-256color",
                        "LANG=C.UTF-8",
                        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                });
        return new ExecResult(r.exitCode, r.output, "",
                System.currentTimeMillis() - t0);
    }

    /**
     * 本地直接执行 —— 在当前设备上走不通，仅保留用于诊断对比。
     *
     * 实测：App 的 untrusted_app 域不能 execve 私有目录里的任何 ELF，
     * 会 EACCES 且 logcat 无 avc 记录。这里保留是为了在没有 Shizuku 的
     * 旧设备上还有一线可能，失败时给出可操作的提示。
     */
    private ExecResult execDirect(String command, long timeoutMs) throws IOException {
        throw new IOException(
                "本机无法直接运行沙箱：Android 禁止普通应用执行自己目录里的程序。"
                + "\n请在工具栏授予 Shizuku 权限，沙箱会改用 shell 身份运行。"
                + "\n当前 Shizuku 状态：" + ShizukuShell.stateText());
    }

    /**
     * 清空沙箱（卸载）。
     *
     * rootfs 在 /data/local/tmp，必须靠 Shizuku 删（App 没权限）。
     * 同时清掉 App 侧留下的 tar.xz 与暂存目录。
     */
    public void uninstall() {
        // 运行期目录（shell 域）由 Shizuku 清理
        if (ShizukuShell.isReady()) {
            try {
                ShizukuShell.exec("rm -rf '" + RUNTIME_HOME + "'", 60000);
            } catch (Throwable ignored) {
            }
        }
        deleteRecursive(stagingRootfs());
        deleteRecursive(new File(getExternalFilesDirSafe(), "staging-ready"));
        deleteRecursive(new File(home(), "rootfs"));
        deleteRecursive(new File(home(), "stage"));
        deleteRecursive(new File(home(), "rootfs.tar.xz"));
    }

    private File getExternalFilesDirSafe() {
        File d = appCtx.getExternalFilesDir(null);
        return d != null ? d : appCtx.getFilesDir();
    }

    /** 沙箱占用空间（字节）。运行期 rootfs 在 /data/local/tmp，App 读不到，
     *  这里只算 App 侧可见的部分（tar.xz 等）。 */
    public long diskUsage() {
        return sizeOf(new File(home(), "rootfs.tar.xz"))
                + sizeOf(stagingRootfs());
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 从 assets 拷贝到目标文件。 */
    private void copyAsset(String assetPath, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null) ensure(parent);

        InputStream in = null;
        OutputStream out = null;
        try {
            // proot 放在 assets/proot/ 下
            in = appCtx.getAssets().open(assetPath);
            out = new FileOutputStream(dest);
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
        Log.i(TAG, "asset " + assetPath + " -> " + dest + " (" + dest.length() + " bytes)");
    }

    /** 读一个流到 StringBuilder 的后台线程。不读会阻塞子进程（管道写满）。 */
    private static Thread pump(final InputStream in, final StringBuilder sink) {
        Thread t = new Thread(() -> {
            BufferedReader r = null;
            try {
                r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) {
                    sink.append(buf, 0, n);
                    // 防爆：单个流超过 4MB 就丢弃后续，避免模型上下文被刷爆
                    if (sink.length() > 4 * 1024 * 1024) break;
                }
            } catch (Throwable ignored) {
            } finally {
                closeQuietly(r);
            }
        }, "sandbox-pump");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** 跑一个进程并等它结束，同时把输出行回调出去（解压进度用）。 */
    private int execWait(String[] cmd, final Progress cb) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            int lines = 0;
            while ((line = r.readLine()) != null) {
                lines++;
                // tar 默认不输出进度，这里按行数粗略反馈，避免用户以为卡死
                if (cb != null && lines % 200 == 0) {
                    cb.onStage("解压中… 已处理 " + lines + " 个条目", 10 + Math.min(75, lines / 100));
                }
            }
        } finally {
            closeQuietly(r);
        }

        try {
            return p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return -1;
        }
    }

    private static void ensure(File dir) {
        if (dir != null && !dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
    }

    private static void writeText(File f, String text) {
        FileOutputStream o = null;
        try {
            ensure(f.getParentFile());
            o = new FileOutputStream(f);
            o.write(text.getBytes("UTF-8"));
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(o);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (Throwable ignored) {
            }
        }
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursive(k);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static long sizeOf(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long sum = 0;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) sum += sizeOf(k);
        }
        return sum;
    }

    /** 人类可读的大小。 */
    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double k = bytes / 1024.0;
        if (k < 1024) return String.format(java.util.Locale.US, "%.1f KB", k);
        double m = k / 1024.0;
        if (m < 1024) return String.format(java.util.Locale.US, "%.1f MB", m);
        return String.format(java.util.Locale.US, "%.2f GB", m / 1024.0);
    }

    /** 设备是否支持（只能 arm64，因为 proot 是 aarch64 编译的）。 */
    public static boolean isSupported() {
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";
        return "arm64-v8a".equals(abi);
    }
}