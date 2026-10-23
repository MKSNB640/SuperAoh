package com.superaoh.core.sandbox;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

// ---------------------------------------------------------------------------
// 接收 Shizuku binder 的桥
//
// 为什么不用官方的 rikka.shizuku.ShizukuProvider：
//   它内部会调 Shizuku.onBinderReceived()，而 Shizuku 类引用了
//   moe.shizuku.server.IShizukuApplication$Stub —— 这个桩类只在 Shizuku
//   服务端存在，客户端 API jar 里没有。结果 Shizuku 一回调就
//   NoClassDefFoundError，把整个 App 崩掉（真机已验证，两次 FATAL）。
//
//   所以这里只做一件事：从 Bundle 里把 IBinder 捞出来交给 ShizukuShell，
//   完全绕开官方 Shizuku 类。协议见 ShizukuShell 的类注释。
// ---------------------------------------------------------------------------

public final class ShizukuBridgeProvider extends ContentProvider {

    private static final String TAG = "ShizukuBridge";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        try {
            if (getContext() != null) {
                ShizukuShell.setPackageName(getContext().getPackageName());
            }
            ShizukuShell.acceptBinderFromBundle(extras);
            // 收到 binder 后立刻 attach —— 这是 Shizuku 建立「已连接客户端」关系的
            // 唯一途径。不 attach 的话服务端会拒绝一切调用，logcat 里表现为
            //   UserServiceRecord: Caller (uid xxxxx) is not an attached client
            ShizukuShell.attach();
        } catch (Throwable t) {
            Log.w(TAG, "call failed", t);
        }
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}