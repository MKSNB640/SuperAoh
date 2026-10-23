package com.superaoh.core.sandbox;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

// ---------------------------------------------------------------------------
// Shizuku 通道（不依赖官方 API jar 里的服务端桩类）
//
// 为什么需要它（这是这台机器上唯一能跑起 Ubuntu 的路子）：
//
//   Android 10+ 起，应用进程被划进 SELinux 的 untrusted_app 域。实测
//   （一加 ColorOS / Android 15 / SDK 35）该域下：
//     - /data/app/**/lib/arm64/ 里的文件可以 exec（proot 本体放这里没问题）
//     - 应用私有目录 /data/user/0/<pkg>/files/** 里的文件一律 exec 失败，EACCES
//       哪怕文件 755、属主是自己、SELinux 上下文 app_data_file 且 category 匹配
//   这直接判了 proot 死刑：proot 自身能起，但它去 execve rootfs 里的
//   /usr/bin/env 时内核在宿主侧就拒绝：
//     proot error: execve("/usr/bin/env"): Permission denied
//
//   Shizuku 用 ADB/root 起了一个 uid=2000(shell) 的常驻进程。通过它执行命令时：
//     - 进程域是 shell，不受 untrusted_app 那条 exec 限制
//     - 能 exec 私有目录里的二进制
//
//   执行路径：
//     App --binder--> shizuku_server(shell) --fork/exec--> proot --> Ubuntu
//
// 为什么不直接用官方的 dev.rikka.shizuku:api：
//   官方 API 的 Shizuku 类会引用 moe.shizuku.server.IShizukuApplication$Stub，
//   而这个桩类只有服务端才有。把它声明进 Manifest 的 ShizukuProvider 一旦收到
//   Shizuku 回调就 NoClassDefFoundError，直接崩掉整个 App（实测已验证）。
//   所以这里不走官方入口，改为手工走 binder 协议 —— 协议很短且稳定：
//     descriptor  "moe.shizuku.server.IShizukuService"
//     transaction 0x8 = newProcess(String[] cmd, String[] env, String dir)
//     返回        readStrongBinder() 得到 IRemoteProcess
//   transaction 号来自 Operit 内置的 IShizukuService$Stub（baksmali 反汇编确认
//   TRANSACTION_newProcess = 0x8），不是猜的。
// ---------------------------------------------------------------------------

public final class ShizukuShell {

    private static final String TAG = "ShizukuShell";

    /** Shizuku 服务的 binder 描述符。 */
    private static final String DESCRIPTOR = "moe.shizuku.server.IShizukuService";
    /** IShizukuService.newProcess(String[], String[], String) 的 transaction id。 */
    private static final int TX_NEW_PROCESS = 0x8;
    /** IShizukuService.checkSelfPermission()。返回已授权的包名列表（RemoteException 表示未授权）。 */
    private static final int TX_CHECK_SELF_PERMISSION = 0x10;
    /** IShizukuService.requestPermission(int requestCode)。 */
    private static final int TX_REQUEST_PERMISSION = 0xf;
    /** IShizukuService.attachApplication(IShizukuApplication, Bundle)。 */
    private static final int TX_ATTACH_APPLICATION = 0x12;

    /** IShizukuApplication 的 binder 描述符。 */
    private static final String APP_DESCRIPTOR = "moe.shizuku.server.IShizukuApplication";

    // IShizukuApplication 的两个回调（oneway）。AIDL 里方法顺序决定 transaction 号：
    //   1 = dispatchRequestPermissionResult(int, Bundle)
    //   2 = dispatchAttachResult(Bundle)
    // 这两个号只用于打日志分类；实际授权状态一律以 checkSelfPermission 为准，
    // 所以下面 onTransact 里对未知 code 也做容错，绝不因为回调格式不符而让进程崩掉。
    private static final int TX_APP_DISPATCH_REQUEST_PERMISSION_RESULT = 1;
    private static final int TX_APP_DISPATCH_ATTACH_RESULT = 2;

    /** attach 时 Bundle 的 key（来自 ShizukuApiConstants，已用 javap 核对过字面量）。 */
    private static final String KEY_ATTACH_PKG = "shizuku:attach-package-name";
    private static final String KEY_ATTACH_API_VERSION = "shizuku:attach-api-version";
    private static final String KEY_ATTACH_GRANTED = "shizuku:attach-reply-permission-granted";

    /** Shizuku 通过 ContentProvider 下发 binder 时用的 Bundle key。 */
    private static final String KEY_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";

    /** 拿到的服务端 binder。null 表示尚未收到。 */
    private static volatile IBinder sBinder;

    private ShizukuShell() {
    }

    // ------------------------------------------------------------------
    // 接收 binder
    // ------------------------------------------------------------------

    /**
     * 从一个 Bundle 里取出 Shizuku 服务端 binder。
     *
     * Shizuku 启动后会调用所有声明了 V3_SUPPORT 的应用的 ContentProvider，
     * 把 binder 打包成 BinderContainer 放进这个 Bundle。
     * 我们的 ShizukuBridgeProvider 收到后调用这个方法。
     */
    public static void acceptBinderFromBundle(Bundle bundle) {
        if (bundle == null) return;
        try {
            bundle.setClassLoader(ShizukuShell.class.getClassLoader());
            Parcelable p = bundle.getParcelable(KEY_BINDER);
            if (p == null) return;
            IBinder b = extractBinder(p);
            if (b != null) {
                sBinder = b;
                Log.i(TAG, "收到 Shizuku binder: " + b);
            }
        } catch (Throwable t) {
            Log.w(TAG, "解析 Shizuku binder 失败", t);
        }
    }

    /** BinderContainer 只有一个 public IBinder 字段，反射取即可。 */
    private static IBinder extractBinder(Object container) {
        try {
            java.lang.reflect.Field f = container.getClass().getField("binder");
            Object v = f.get(container);
            if (v instanceof IBinder) return (IBinder) v;
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    /** 是否已拿到 binder 且服务活着。 */
    public static boolean isBinderAvailable() {
        IBinder b = sBinder;
        return b != null && b.pingBinder();
    }

    /**
     * 该应用是否已被 Shizuku 授权。
     *
     * 协议（对照官方 rikka.shizuku.Shizuku.checkSelfPermission 字节码）：
     *   transact(0x10) -> readException() -> readInt() != 0
     * 服务端返回 boolean；未授权时返回 false（不抛异常）。
     * 早期实现误以为「未授权会抛 SecurityException」，导致永远判成 false。
     */
    public static boolean hasPermission() {
        IBinder b = sBinder;
        if (b == null || !b.pingBinder()) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!b.transact(TX_CHECK_SELF_PERMISSION, data, reply, 0)) {
                Log.w(TAG, "checkSelfPermission: transact 返回 false");
                return false;
            }
            reply.readException();
            boolean granted = reply.readInt() != 0;
            Log.i(TAG, "checkSelfPermission -> " + granted);
            return granted;
        } catch (Throwable t) {
            // 未 attach 时服务端会抛 SecurityException（"is not an attached client"）
            Log.w(TAG, "checkSelfPermission 异常: " + t.getMessage());
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * 发起授权请求。
     *
     * Shizuku 有个前置要求：应用必须先 attachApplication（把回调用的
     * IShizukuApplication binder 交给它），之后 requestPermission 才被受理，
     * 否则服务端会直接拒绝。attach 之后再 request，Shizuku 就会弹确认框。
     *
     * @return 请求是否成功发出（不代表已授权）
     */
    public static boolean requestPermission(int requestCode) {
        IBinder b = sBinder;
        if (b == null || !b.pingBinder()) return false;

        // 1) 先 attach
        try {
            attachApplication(b);
        } catch (Throwable t) {
            Log.w(TAG, "attachApplication 失败: " + t.getMessage());
            // attach 失败也继续试一次 request，有些版本不需要 attach
        }

        // 2) 再请求权限
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(requestCode);
            if (!b.transact(TX_REQUEST_PERMISSION, data, reply, 0)) return false;
            reply.readException();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "requestPermission 失败: " + t.getMessage());
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** 回调 binder：Shizuku 通过它把「用户点了允许/拒绝」的结果回传给我们。 */
    private static IBinder sAppBinder;

    /**
     * 把一个最小的 IShizukuApplication 交给 Shizuku（对外可见，自检里也要能单独调）。
     *
     * 协议对照官方 rikka.shizuku.Shizuku.attachApplicationV13 字节码：
     *   writeInterfaceToken("moe.shizuku.server.IShizukuService")
     *   writeStrongBinder(appBinder)
     *   writeInt(1)                // hasBundle 标志
     *   Bundle.writeToParcel()
     *   transact(18, ...)
     * Bundle 里两个 key：shizuku:attach-api-version=13、shizuku:attach-package-name=<包名>
     *
     * @return 是否成功发出
     */
    public static boolean attach() {
        IBinder b = sBinder;
        if (b == null || !b.pingBinder()) return false;
        try {
            attachApplication(b);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "attachApplication 失败: " + t.getMessage());
            return false;
        }
    }

    private static synchronized void attachApplication(IBinder service) throws RemoteException {
        if (sAppBinder == null) {
            sAppBinder = new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                        throws RemoteException {
                    try {
                        data.enforceInterface(APP_DESCRIPTOR);
                        if (code == TX_APP_DISPATCH_REQUEST_PERMISSION_RESULT) {
                            int reqCode = data.readInt();
                            Log.i(TAG, "Shizuku 回调：授权结果 requestCode=" + reqCode);
                            if (reply != null) reply.writeNoException();
                            return true;
                        }
                        if (code == TX_APP_DISPATCH_ATTACH_RESULT) {
                            Log.i(TAG, "Shizuku 回调：attach 结果");
                            if (reply != null) reply.writeNoException();
                            return true;
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "onTransact " + code + " 出错: " + t.getMessage());
                    }
                    // 未知 transaction：绝不能抛异常，否则会把整个 App 带崩
                    return true;
                }
            };
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeStrongBinder(sAppBinder);

            Bundle attach = new Bundle();
            attach.putInt(KEY_ATTACH_API_VERSION, 13);
            attach.putString(KEY_ATTACH_PKG, sPackageName);
            data.writeInt(1);
            attach.writeToParcel(data, 0);

            boolean ok = service.transact(TX_ATTACH_APPLICATION, data, reply, 0);
            Log.i(TAG, "attachApplication transact -> " + ok + " package=" + sPackageName);
            if (ok) {
                try {
                    reply.readException();
                } catch (Throwable t) {
                    Log.w(TAG, "attachApplication reply 异常: " + t.getMessage());
                }
            }
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** 包名（attach 时必须与服务端识别到的一致）。 */
    private static String sPackageName = "com.superaoh";

    /** 由 Provider 收到 binder 时把真实包名传进来。 */
    public static void setPackageName(String pkg) {
        if (pkg != null && !pkg.isEmpty()) sPackageName = pkg;
    }

    /** 服务活着且已授权。 */
    public static boolean isReady() {
        return isBinderAvailable() && hasPermission();
    }

    /** 打开 Shizuku 应用，让用户手动授权（最稳，不依赖会崩的官方授权流程）。 */
    public static boolean openShizukuApp(Context ctx) {
        try {
            android.content.Intent i = ctx.getPackageManager()
                    .getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (i == null) return false;
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 给 UI 用的一句话状态。 */
    public static String stateText() {
        if (!isBinderAvailable()) {
            return "未连接（请打开 Shizuku，确认服务已启动）";
        }
        if (!hasPermission()) {
            return "已连接，但未在 Shizuku 里授权本应用";
        }
        return "已授权（shell 身份可用）";
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    public static final class Result {
        public final int exitCode;
        public final String output;
        public final long millis;

        Result(int code, String out, long ms) {
            this.exitCode = code;
            this.output = out;
            this.millis = ms;
        }

        public boolean ok() {
            return exitCode == 0;
        }
    }

    /**
     * 以 shell 身份启动进程（binder 直调）。
     */
    private static Process newProcess(String[] argv, String[] env, String dir)
            throws IOException {
        IBinder b = sBinder;
        if (b == null || !b.pingBinder()) {
            throw new IOException("Shizuku 未连接");
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeStringArray(argv);
            data.writeStringArray(env);
            data.writeString(dir);
            boolean ok = b.transact(TX_NEW_PROCESS, data, reply, 0);
            if (!ok) {
                throw new IOException("Shizuku 拒绝了 newProcess 请求");
            }
            reply.readException();
            IBinder procBinder = reply.readStrongBinder();
            if (procBinder == null) {
                throw new IOException("Shizuku 没有返回进程句柄");
            }
            return new RemoteProcess(procBinder);
        } catch (RemoteException e) {
            throw new IOException("Shizuku 远程调用失败: " + e.getMessage(), e);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // ------------------------------------------------------------------
    // IRemoteProcess 的最小实现
    //
    // 不直接用官方的 ShizukuRemoteProcess：它引用了 moe.shizuku.server.IRemoteProcess
    // 这个桩接口，而该接口不在客户端 jar 里，编译期就过不了。
    // 这里把同一套 binder 协议自己实现一遍（transaction 号同样来自反汇编）：
    //   1 getOutputStream  2 getInputStream  3 getErrorStream
    //   4 waitFor          5 exitValue       6 destroy
    //   7 alive            8 waitForTimeout
    // ------------------------------------------------------------------

    private static final class RemoteProcess extends Process {

        private static final String PROC_DESCRIPTOR = "moe.shizuku.server.IRemoteProcess";
        private static final int TX_GET_OUTPUT = 1;
        private static final int TX_GET_INPUT = 2;
        private static final int TX_GET_ERROR = 3;
        private static final int TX_WAIT_FOR = 4;
        private static final int TX_EXIT_VALUE = 5;
        private static final int TX_DESTROY = 6;
        private static final int TX_ALIVE = 7;
        private static final int TX_WAIT_FOR_TIMEOUT = 8;

        private final IBinder remote;
        private java.io.InputStream in;
        private java.io.InputStream err;
        private java.io.OutputStream out;

        RemoteProcess(IBinder remote) {
            this.remote = remote;
        }

        /** 通用调用：发一个空参数请求，让调用方自己读 reply。 */
        private Parcel begin(int code) throws RemoteException {
            Parcel data = Parcel.obtain();
            data.writeInterfaceToken(PROC_DESCRIPTOR);
            Parcel reply = Parcel.obtain();
            if (!remote.transact(code, data, reply, 0)) {
                data.recycle();
                reply.recycle();
                throw new RemoteException("transact " + code + " 被拒绝");
            }
            data.recycle();
            reply.readException();
            return reply;
        }
        private ParcelFileDescriptor fd(int code) throws IOException {
            Parcel reply = null;
            try {
                reply = begin(code);
                // IRemoteProcess AIDL 的 ParcelFileDescriptor 返回值不是裸 fd：
                // reply 中先写 presence int，再由 PFD.CREATOR 读取 parcelled fd。
                // 直接 readFileDescriptor() 会把 presence 槽当作 fd 读，导致
                // Bad file descriptor / stdout、stderr 永远为空。
                if (reply.readInt() == 0) {
                    Log.w(TAG, "RemoteProcess fd(" + code + ") -> null");
                    return null;
                }
                ParcelFileDescriptor p = ParcelFileDescriptor.CREATOR.createFromParcel(reply);
                Log.i(TAG, "RemoteProcess fd(" + code + ") -> "
                        + (p == null ? "null" : ("fd=" + p.getFd())));
                return p;
            } catch (RemoteException e) {
                Log.w(TAG, "RemoteProcess fd(" + code + ") 失败: " + e.getMessage());
                throw new IOException(e);
            } catch (RuntimeException e) {
                Log.w(TAG, "RemoteProcess fd(" + code + ") 解析失败", e);
                throw new IOException("解析 Shizuku 进程流失败: " + e.getMessage(), e);
            } finally {
                if (reply != null) reply.recycle();
            }
        }


        @Override
        public java.io.OutputStream getOutputStream() {
            if (out == null) {
                try {
                    ParcelFileDescriptor p = fd(TX_GET_OUTPUT);
                    out = new ParcelFileDescriptor.AutoCloseOutputStream(p);
                } catch (Throwable t) {
                    out = new java.io.ByteArrayOutputStream();
                }
            }
            return out;
        }

        @Override
        public java.io.InputStream getInputStream() {
            if (in == null) {
                try {
                    ParcelFileDescriptor p = fd(TX_GET_INPUT);
                    in = new ParcelFileDescriptor.AutoCloseInputStream(p);
                } catch (Throwable t) {
                    in = new java.io.ByteArrayInputStream(new byte[0]);
                }
            }
            return in;
        }

        @Override
        public java.io.InputStream getErrorStream() {
            if (err == null) {
                try {
                    ParcelFileDescriptor p = fd(TX_GET_ERROR);
                    err = new ParcelFileDescriptor.AutoCloseInputStream(p);
                } catch (Throwable t) {
                    err = new java.io.ByteArrayInputStream(new byte[0]);
                }
            }
            return err;
        }

        @Override
        public int waitFor() throws InterruptedException {
            try {
                Parcel reply = begin(TX_WAIT_FOR);
                try {
                    return reply.readInt();
                } finally {
                    reply.recycle();
                }
            } catch (RemoteException e) {
                throw new InterruptedException(e.getMessage());
            }
        }

        @Override
        public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit)
                throws InterruptedException {
            try {
                long ms = unit.toMillis(timeout);
                Parcel data = Parcel.obtain();
                data.writeInterfaceToken(PROC_DESCRIPTOR);
                data.writeLong(ms);
                data.writeString(unit.name());
                Parcel reply = Parcel.obtain();
                boolean ok = remote.transact(TX_WAIT_FOR_TIMEOUT, data, reply, 0);
                data.recycle();
                if (!ok) {
                    reply.recycle();
                    return false;
                }
                try {
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    reply.recycle();
                }
            } catch (RemoteException e) {
                throw new InterruptedException(e.getMessage());
            }
        }

        @Override
        public int exitValue() {
            try {
                Parcel reply = begin(TX_EXIT_VALUE);
                try {
                    return reply.readInt();
                } finally {
                    reply.recycle();
                }
            } catch (RemoteException e) {
                throw new IllegalThreadStateException(e.getMessage());
            }
        }

        @Override
        public void destroy() {
            try {
                begin(TX_DESTROY).recycle();
            } catch (Throwable ignored) {
            }
        }

        @Override
        public boolean isAlive() {
            try {
                Parcel reply = begin(TX_ALIVE);
                try {
                    return reply.readInt() != 0;
                } finally {
                    reply.recycle();
                }
            } catch (Throwable t) {
                return false;
            }
        }
    }

    /**
     * 以 shell 身份执行一条命令。
     *
     * 同步阻塞，必须放后台线程。
     *
     * 注意必须显式传 env：Shizuku 的 newProcess(argv, env, dir) 在 env 为 null 时
     * 不会给子进程继承一份可用的 PATH，结果是 `sh` 起来了但找不到 tar/mkdir 等命令，
     * 表现为「退出码 1、输出全空」——非常难查。所以这里固定给一份标准 PATH。
     */
    public static Result exec(String command, long timeoutMs) throws IOException {
        return execArray(new String[]{"/system/bin/sh", "-c", command},
                "/", timeoutMs, SHELL_ENV);
    }

    /**
     * shell 进程的标准环境。
     *
     * PATH 必须包含 /system/bin：Android 的 sh/tar/mkdir/ls 都在那里，
     * 而 Shizuku 服务端不会替我们补上。
     */
    private static final String[] SHELL_ENV = {
            "PATH=/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin",
            "HOME=/",
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system",
    };

    /**
     * 以 shell 身份执行一个可执行文件（参数数组形式，不经 shell 解析）。
     *
     * proot 的参数里有大量路径和空格，走数组最稳。
     */
    public static Result execArray(String[] argv, String workdir, long timeoutMs)
            throws IOException {
        return execArray(argv, workdir, timeoutMs, null);
    }

    /**
     * 同上，但可以显式给环境变量（"K=V" 形式）。
     *
     * 传了 env 时服务端会用这份环境启动进程（不会继承宿主环境），
     * 这对 proot 很重要：宿主环境里没有 PROOT_TMP_DIR，而 proot 必须用它。
     */
    public static Result execArray(String[] argv, String workdir, long timeoutMs,
                                  String[] env) throws IOException {
        long t0 = System.currentTimeMillis();
        Process p = newProcess(argv, env, workdir);

        StringBuilder sb = new StringBuilder();
        Thread to = pump(p.getInputStream(), sb);
        Thread te = pump(p.getErrorStream(), sb);

        boolean finished;
        try {
            finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try { p.destroy(); } catch (Throwable ignored) { }
            throw new IOException("命令被中断");
        }

        if (!finished) {
            try { p.destroy(); } catch (Throwable ignored) { }
            joinQuietly(to);
            joinQuietly(te);
            return new Result(-1, sb + "\n[超时 " + (timeoutMs / 1000) + "s，已强制终止]",
                    System.currentTimeMillis() - t0);
        }

        joinQuietly(to);
        joinQuietly(te);
        int code;
        try {
            code = p.exitValue();
        } catch (Throwable t) {
            code = -1;
        }
        Log.i(TAG, "execArray exit=" + code + " outLen=" + sb.length()
                + " out=" + (sb.length() > 300 ? sb.substring(0, 300) : sb.toString()));
        return new Result(code, sb.toString(), System.currentTimeMillis() - t0);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static Thread pump(final java.io.InputStream in, final StringBuilder sink) {
        Thread t = new Thread(() -> {
            BufferedReader r = null;
            try {
                r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) {
                    sink.append(buf, 0, n);
                    if (sink.length() > 4 * 1024 * 1024) break;
                }
            } catch (Throwable ignored) {
            } finally {
                if (r != null) try { r.close(); } catch (Throwable ignored) { }
            }
        }, "shizuku-pump");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void joinQuietly(Thread t) {
        if (t == null) return;
        try {
            t.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}