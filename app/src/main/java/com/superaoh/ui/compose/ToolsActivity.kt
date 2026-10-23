package com.superaoh.ui.compose

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superaoh.core.ToolRuntime
import com.superaoh.core.mcp.McpManager
import com.superaoh.core.sandbox.UbuntuSandbox
import com.superaoh.core.tool.ToolRegistry
import com.superaoh.core.tool.ToolSpec

// ---------------------------------------------------------------------------
// 工具与扩展管理页
//
// 一个页面管四件事：
//   1. Ubuntu 沙箱 —— 安装 / 卸载 / 看占用
//   2. MCP 服务器 —— 增删改、连接测试
//   3. 插件        —— 列表 + 重载（文件放私有目录，用文件管理器放进去）
//   4. 技能        —— 列表 + 重载
// 外加一个「全部工具」清单，按来源分组，可以直观确认模型到底能看到什么。
//
// 为什么用 Compose 而不是像 AiSettingsActivity 那样手搓 View：
//   这个页面条目数量不定（MCP 服务器、插件、技能都是列表），
//   手搓 View 要写大量 addView/removeView 和状态同步，Compose 的
//   LazyColumn + mutableStateOf 天然适合。而且聊天页已经是 Compose，
//   主题色可以直接复用。
// ---------------------------------------------------------------------------

private val TBgDeep = Color(0xFF0B0E14)
private val TBgSurface = Color(0xFF151A23)
private val TBgElevated = Color(0xFF1B2130)
private val TTextPrimary = Color(0xFFE8EBF5)
private val TTextSecondary = Color(0xFF8B93A7)
private val TTextDim = Color(0xFF5A6274)
private val TAccent = Color(0xFF5B8CFF)
private val TOkGreen = Color(0xFF3FB950)
private val TErrRed = Color(0xFFF85149)
private val TDivider = Color(0xFF232A38)

class ToolsActivity : ComponentActivity() {

    private val ui = Handler(Looper.getMainLooper())

    /** 沙箱安装状态。 */
    private val sandboxInstalling = mutableStateOf(false)
    private val sandboxProgress = mutableStateOf(0)
    private val sandboxStage = mutableStateOf("")

    /** 版本号 +1 触发列表重绘（数据本身在 ToolRuntime 里，不是 Compose 状态）。 */
    private val revision = mutableStateOf(0)

    /** MCP 编辑对话框的目标；null 表示关闭。 */
    private val editing = mutableStateOf<McpManager.Server?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 调试入口：am start 带 --ez auto_install true 时直接开始装沙箱。
        // 目的是让自动化测试能在不点屏幕的前提下验证解压链路，
        // 正常用户不会走到这个分支。
        if (intent?.getBooleanExtra("auto_install", false) == true) {
            installSandbox()
        }

        // 自检入口：--ez self_test true
        //
        // 为什么必须在 App 进程里跑：adb 的 run-as 进的是 shell 的挂载命名空间，
        // 看不到 App 自己的 FUSE 视图，/storage/emulated/0 在那里是 Permission denied，
        // 结论不可信。只有这里跑出来的才是用户真实会遇到的结果。
        // 结果写到 getExternalFilesDir/self_test.txt，adb 直接 pull 就能看。
        if (intent?.getBooleanExtra("self_test", false) == true) {
            runSelfTest()
        }

        setContent {
            val rev = revision.value
            Column(Modifier.fillMaxSize().background(TBgDeep)) {
                TopBar(onBack = { finish() })
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    SandboxSection(
                        installing = sandboxInstalling.value,
                        progress = sandboxProgress.value,
                        stage = sandboxStage.value,
                        onInstall = { installSandbox() },
                        onUninstall = { uninstallSandbox() },
                    )
                    Spacer(Modifier.height(8.dp))
                    McpSection(
                        rev = rev,
                        onAdd = { editing.value = McpManager.Server() },
                        onEdit = { editing.value = it },
                        onDelete = { deleteServer(it) },
                        onConnect = { connectServer(it) },
                        onDisconnect = { disconnectServer(it) },
                    )
                    Spacer(Modifier.height(8.dp))
                    PluginSection(rev = rev, onReload = { reloadPlugins() })
                    Spacer(Modifier.height(8.dp))
                    SkillSection(rev = rev, onReload = { reloadSkills() })
                    Spacer(Modifier.height(8.dp))
                    ToolListSection(rev = rev)
                    Spacer(Modifier.height(24.dp))
                }
            }

            editing.value?.let { srv ->
                McpEditDialog(
                    server = srv,
                    onDismiss = { editing.value = null },
                    onSave = { saved ->
                        ToolRuntime.get(this).mcp().upsert(saved)
                        editing.value = null
                        bump()
                        Toast.makeText(this, "已保存，正在连接…", Toast.LENGTH_SHORT).show()
                        connectServer(saved.name)
                    },
                )
            }
        }
    }

    private fun bump() {
        revision.value = revision.value + 1
    }

    // ------------------------------------------------------------------
    // 自检（仅测试用）
    // ------------------------------------------------------------------

    /**
     * 在 App 进程内跑一遍沙箱链路，把结果写成文本文件。
     *
     * 覆盖的点：
     *   1. 共享存储在本进程里到底能不能读（决定 /sdcard 能否绑真路径）
     *   2. proot 能否被 exec（error=13 的老问题是否真的解决了）
     *   3. 沙箱内 uid / 内核 / /sdcard 内容是否符合预期
     *   4. 沙箱内能否真正读写 /sdcard
     */
    private fun runSelfTest() {
        Thread(Runnable {
            val sb = StringBuilder()
            fun line(s: String) {
                sb.append(s).append('\n')
            }
            try {
                val rt = ToolRuntime.get(this)
                val box = rt.sandbox()

                line("=== SuperAoh 沙箱自检 ===")
                line("sdk=" + android.os.Build.VERSION.SDK_INT)
                line("abi=" + android.os.Build.SUPPORTED_ABIS.firstOrNull())
                line("nativeLibraryDir=" + applicationInfo.nativeLibraryDir)
                line("prootBin=" + box.prootBin().absolutePath)
                line("proot exists=" + box.prootBin().exists()
                        + " canExec=" + box.prootBin().canExecute()
                        + " size=" + box.prootBin().length())
                line("isInstalled=" + box.isInstalled())
                line("hasAllFilesAccess=" + box.hasAllFilesAccess())
                line("shizuku.binder=" + com.superaoh.core.sandbox.ShizukuShell.isBinderAvailable()
                        + " perm=" + com.superaoh.core.sandbox.ShizukuShell.hasPermission()
                        + " ready=" + com.superaoh.core.sandbox.ShizukuShell.isReady())
                // 自检时如果还没授权，主动发一次请求，方便用 adb 点「允许」后立刻复测。
                if (com.superaoh.core.sandbox.ShizukuShell.isBinderAvailable()
                        && !com.superaoh.core.sandbox.ShizukuShell.hasPermission()) {
                    val sent = com.superaoh.core.sandbox.ShizukuShell.requestPermission(0x5A1)
                    line("已发起授权请求: " + sent)
                }
                line("sharedStorageRoot=" + (box.sharedStorageRoot()?.absolutePath ?: "null"))
                line("sharedStorageState=" + box.sharedStorageState())
                line("filesDir=" + filesDir.absolutePath)
                line("externalFilesDir=" + (getExternalFilesDir(null)?.absolutePath ?: "null"))

                // --- 探针 X：App 能否写 /data/local/tmp ---
                // 这决定了整个沙箱的架构：
                //   shell(uid 2000) 读不到 /data/user/0/<pkg>/files（实测 Permission denied），
                //   所以如果走 Shizuku，rootfs 就必须放在 shell 能读的地方；
                //   而 /data/local/tmp 是唯一能 exec 的可写区（外部存储是 noexec，实测 126）。
                //   若 App 自己写不进去，就只能让 Shizuku 侧（shell）自己解压。
                line("--- 探针 X：/data/local/tmp 可写性 ---")
                try {
                    val tmp = java.io.File("/data/local/tmp")
                    line("exists=" + tmp.exists() + " canRead=" + tmp.canRead()
                            + " canWrite=" + tmp.canWrite() + " canExec=" + tmp.canExecute())
                    val probe = java.io.File(tmp, "sa_probe_" + android.os.Process.myUid())
                    probe.writeText("probe")
                    line("!! 竟然可写：" + probe.absolutePath)
                    probe.delete()
                } catch (t: Throwable) {
                    line("!! 不可写: " + t.javaClass.simpleName + ": " + t.message)
                }

                // --- 探针 Y：shell 预先建的 0777 子目录能不能写 ---
                // /data/local/tmp 本身是 0771 shell:shell，App 进不去；
                // 但如果 shell 先建一个 0777 的子目录，App 也许能写进去。
                // 能写的话，rootfs 就可以直接由 App 解压到这里，不必二次搬运 700MB。
                line("--- 探针 Y：/data/local/tmp/superaoh（0777）可写性 ---")
                try {
                    val sub = java.io.File("/data/local/tmp/superaoh")
                    line("exists=" + sub.exists() + " canRead=" + sub.canRead()
                            + " canWrite=" + sub.canWrite())
                    val probe = java.io.File(sub, "sa_probe_y")
                    probe.writeText("probe")
                    line("!! 可写：" + probe.absolutePath)
                    probe.delete()
                } catch (t: Throwable) {
                    line("!! 不可写: " + t.javaClass.simpleName + ": " + t.message)
                }

                // 直接试私有目录里不带 .so 后缀的 proot。
                // 依据：同机 Operit 的 proot 就在 /data/user/0/<pkg>/files/usr/bin/proot，
                // 名字没有 .so 后缀，跑得很好（它的进程 Seccomp=2 也开着）。
                // 而我们放在 nativeLibraryDir 的 libproot.so 虽然能被 exec，
                // 但一进去就 chmod ENOSYS —— 怀疑与 .so 名字/加载方式有关。
                line("")
                line("--- 执行测试 0f：exec /data/user/0 路径下的副本 ---")
                try {
                    val src = java.io.File(box.rootfs(), "usr/bin/env")
                    val dst = java.io.File("/data/user/0/" + packageName + "/files/env_test2")
                    line("dst=" + dst.absolutePath)
                    src.inputStream().use { ins -> dst.outputStream().use { outs -> ins.copyTo(outs) } }
                    dst.setExecutable(true, false)
                    line("len=" + dst.length() + " canExec=" + dst.canExecute())
                    val p0 = ProcessBuilder(dst.absolutePath, "--version")
                        .redirectErrorStream(true).start()
                    val o0 = p0.inputStream.bufferedReader().readText()
                    p0.waitFor()
                    line("exit=" + p0.exitValue() + " out<<<" + o0.trim().take(120) + ">>>")
                    dst.delete()
                } catch (t: Throwable) {
                    line("!! " + t)
                }

                line("")
                line("--- 执行测试 0e：exec 复制过来的 libproot.so（nativeLibraryDir 副本） ---")
                try {
                    val lib = java.io.File(applicationInfo.nativeLibraryDir, "libproot.so")
                    line("lib exists=" + lib.exists() + " canExec=" + lib.canExecute())
                    val p0 = ProcessBuilder(lib.absolutePath, "--version")
                        .redirectErrorStream(true).start()
                    val o0 = p0.inputStream.bufferedReader().readText()
                    p0.waitFor()
                    line("exit=" + p0.exitValue() + " out<<<" + o0.trim().take(120) + ">>>")
                } catch (t: Throwable) {
                    line("!! " + t)
                }

                line("")
                line("--- 执行测试 0c：App 进程直接 exec rootfs 里的二进制 ---")
                try {
                    val envBin = java.io.File(box.rootfs(), "usr/bin/env")
                    line("env exists=" + envBin.exists() + " canExec=" + envBin.canExecute()
                            + " len=" + envBin.length())
                    val p0 = ProcessBuilder(envBin.absolutePath, "--version")
                        .redirectErrorStream(true).start()
                    val o0 = p0.inputStream.bufferedReader().readText()
                    p0.waitFor()
                    line("exit=" + p0.exitValue() + " out<<<" + o0.trim().take(200) + ">>>")
                } catch (t: Throwable) {
                    line("!! 直接 exec 失败: " + t)
                }

                line("")
                line("--- 执行测试 0d：把二进制拷到 files 根再 exec ---")
                try {
                    val src = java.io.File(box.rootfs(), "usr/bin/env")
                    val dst = java.io.File(filesDir, "env_test")
                    src.inputStream().use { ins -> dst.outputStream().use { outs -> ins.copyTo(outs) } }
                    dst.setExecutable(true, false)
                    line("dst=" + dst.absolutePath + " len=" + dst.length()
                            + " canExec=" + dst.canExecute())
                    val p0 = ProcessBuilder(dst.absolutePath, "--version")
                        .redirectErrorStream(true).start()
                    val o0 = p0.inputStream.bufferedReader().readText()
                    p0.waitFor()
                    line("exit=" + p0.exitValue() + " out<<<" + o0.trim().take(200) + ">>>")
                    dst.delete()
                } catch (t: Throwable) {
                    line("!! 拷贝 exec 失败: " + t)
                }

                line("")
                line("--- 执行测试 0：私有目录 proot（无 .so 后缀） ---")
                try {
                    val copy = java.io.File(box.home(), "proot")
                    if (!copy.exists() || copy.length() == 0L) {
                        box.prootBin().inputStream().use { ins ->
                            copy.outputStream().use { outs -> ins.copyTo(outs) }
                        }
                    }
                    copy.setExecutable(true, false)
                    val p0 = ProcessBuilder(
                        copy.absolutePath, "--version").redirectErrorStream(true).start()
                    val o0 = p0.inputStream.bufferedReader().readText()
                    p0.waitFor()
                    line("exit=" + p0.exitValue())
                    line("out<<<" + o0.trim().lines().take(3).joinToString(" | ") + ">>>")
                } catch (t: Throwable) {
                    line("!! " + t)
                }

                line("")
                line("--- 执行测试 0b：私有目录 proot 跑完整沙箱 ---")
                try {
                    val copy = java.io.File(box.home(), "proot")
                    val cmd = listOf(
                        copy.absolutePath, "--link2symlink", "-0",
                        "-r", box.rootfs().absolutePath,
                        "-b", "/dev", "-b", "/proc", "-b", "/sys",
                        "-w", "/root",
                        box.rootfs().absolutePath + "/usr/bin/env", "-i",
                        "HOME=/root", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                        "/bin/bash", "-lc", "echo PRIVATE_PROOT_OK; id; uname -m",
                    )
                    val pb0 = ProcessBuilder(cmd).redirectErrorStream(true)
                    pb0.directory(box.home())
                    pb0.environment()["PROOT_TMP_DIR"] = java.io.File(box.home(), "tmp").absolutePath
                    val p0 = pb0.start()
                    val o0 = p0.inputStream.bufferedReader().readText()
                    p0.waitFor()
                    line("exit=" + p0.exitValue())
                    line("out<<<" + o0.trim() + ">>>")
                } catch (t: Throwable) {
                    line("!! " + t)
                }

                if (!box.isInstalled()) {
                    line("!! 沙箱未安装，跳过执行测试")
                } else {
                    // --- 决定性测试 Z：换一个 proot 二进制 ---
                    // 已排除：SELinux 域（同 untrusted_app）、category、seccomp、
                    // NoNewPrivs、CapEff、权限位、属主、文件系统、路径写法。
                    // 唯一没换过的变量就是 proot 二进制本身。
                    // Operit 的 liboperit_proot.so 与我们的 libproot.so 大小相同
                    // （都 256864），但内容是否一致未验证 —— 直接实测。
                    line("")
                    line("--- 执行测试 Z：用 Operit 的 proot 二进制跑我们的 rootfs ---")
                    try {
                        val opApk = packageManager
                            .getPackageInfo("com.ai.assistance.operit", 0).applicationInfo
                        val opProot = java.io.File(opApk.nativeLibraryDir, "liboperit_proot.so")
                        line("operitProot=" + opProot.absolutePath + " exists=" + opProot.exists())
                        if (!opProot.exists()) {
                            line("!! Operit 未安装或没有该二进制，跳过")
                        } else {
                            val dst = java.io.File(filesDir, "proot_op_test")
                            opProot.inputStream().use { ins ->
                                dst.outputStream().use { outs -> ins.copyTo(outs) }
                            }
                            dst.setExecutable(true, false)
                            line("copied=" + dst.absolutePath + " len=" + dst.length())
                            val argv = box.buildCommand("echo PROOT_OK; id").toMutableList()
                            argv[0] = dst.absolutePath
                            val pz = ProcessBuilder(argv)
                                .directory(box.home())
                                .redirectErrorStream(true)
                                .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
                                .start()
                            pz.outputStream.close()
                            val oz = pz.inputStream.bufferedReader().readText()
                            pz.waitFor()
                            line("exit=" + pz.exitValue())
                            line("out<<<" + oz.trim().take(800) + ">>>")
                            dst.delete()
                        }
                    } catch (t: Throwable) {
                        line("!! " + t)
                    }

                    line("")
                    line("--- 执行测试 1：基本链路 ---")
                    val r1 = box.exec(
                        "echo PROOT_OK; id; uname -m; echo HOME=\$HOME; pwd", 60000)
                    line("exit=" + r1.exitCode)
                    line("out<<<" + r1.stdout.trim() + ">>>")
                    line("err<<<" + r1.stderr.trim() + ">>>")

                    line("")
                    line("--- 执行测试 2：/sdcard 可见性 ---")
                    val r2 = box.exec(
                        "ls -la /sdcard 2>&1 | head -20; echo '---'; " +
                                "ls /sdcard >/dev/null 2>&1 && echo SDCARD_LIST_OK || echo SDCARD_LIST_FAIL",
                        60000)
                    line("exit=" + r2.exitCode)
                    line("out<<<" + r2.stdout.trim() + ">>>")
                    line("err<<<" + r2.stderr.trim() + ">>>")

                    line("")
                    line("--- 执行测试 3：/sdcard 写入 ---")
                    val r3 = box.exec(
                        "echo hello-from-sandbox > /sdcard/_sa_test.txt 2>&1 && " +
                                "cat /sdcard/_sa_test.txt && rm -f /sdcard/_sa_test.txt && " +
                                "echo SDCARD_WRITE_OK || echo SDCARD_WRITE_FAIL",
                        60000)
                    line("exit=" + r3.exitCode)
                    line("out<<<" + r3.stdout.trim() + ">>>")
                    line("err<<<" + r3.stderr.trim() + ">>>")

                    line("")
                    line("--- 执行测试 4：rootfs 挂载点 ---")
                    val r4 = box.exec(
                        "ls -ld /sdcard /storage 2>&1; echo '---'; " +
                                "df -h / 2>&1 | head -3", 60000)
                    line("exit=" + r4.exitCode)
                    line("out<<<" + r4.stdout.trim() + ">>>")
                    line("err<<<" + r4.stderr.trim() + ">>>")
                }
            } catch (t: Throwable) {
                line("!! 自检异常: " + t)
            }

            val text = sb.toString()
            android.util.Log.i("SelfTest", text)
            try {
                val dir = getExternalFilesDir(null) ?: filesDir
                dir.mkdirs()
                java.io.File(dir, "self_test.txt").writeText(text, Charsets.UTF_8)
            } catch (t: Throwable) {
                android.util.Log.e("SelfTest", "写文件失败", t)
            }
        }, "sandbox-selftest").start()
    }

    // ------------------------------------------------------------------
    // 沙箱
    // ------------------------------------------------------------------

    private fun installSandbox() {
        if (sandboxInstalling.value) return
        val rt = ToolRuntime.get(this)
        if (!UbuntuSandbox.isSupported()) {
            Toast.makeText(this, "本机架构不支持（需 arm64-v8a）", Toast.LENGTH_LONG).show()
            return
        }
        sandboxInstalling.value = true
        sandboxProgress.value = 0
        sandboxStage.value = "准备中…"

        Thread(Runnable {
            try {
                rt.sandbox().install(object : UbuntuSandbox.Progress {
                    override fun onStage(message: String?, percent: Int) {
                        ui.post {
                            sandboxStage.value = message ?: ""
                            // 进度只增不减，避免解压阶段的百分比回跳引起误解
                            if (percent > sandboxProgress.value) {
                                sandboxProgress.value = percent
                            }
                        }
                    }
                })
                ui.post {
                    sandboxInstalling.value = false
                    sandboxProgress.value = 100
                    bump()
                    Toast.makeText(this, "沙箱安装完成", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                ui.post {
                    sandboxInstalling.value = false
                    sandboxStage.value = "失败：" + (t.message ?: t.javaClass.simpleName)
                    Toast.makeText(this, "安装失败：" + t.message, Toast.LENGTH_LONG).show()
                }
            }
        }, "sandbox-install").start()
    }

    private fun uninstallSandbox() {
        val rt = ToolRuntime.get(this)
        Thread(Runnable {
            try {
                rt.sandbox().uninstall()
                ui.post {
                    bump()
                    Toast.makeText(this, "已卸载", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                ui.post { Toast.makeText(this, "卸载失败：" + t.message, Toast.LENGTH_LONG).show() }
            }
        }, "sandbox-uninstall").start()
    }

    // ------------------------------------------------------------------
    // MCP
    // ------------------------------------------------------------------

    private fun connectServer(name: String) {
        val rt = ToolRuntime.get(this)
        Thread(Runnable {
            val ok = rt.mcp().connect(name)
            ui.post {
                bump()
                val s = rt.mcp().find(name)
                val msg = if (ok) {
                    "已连接，注册 " + (s?.toolCount ?: 0) + " 个工具"
                } else {
                    "连接失败：" + (s?.lastError ?: "未知错误")
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }, "mcp-connect").start()
    }

    private fun disconnectServer(name: String) {
        ToolRuntime.get(this).mcp().disconnect(name)
        bump()
    }

    private fun deleteServer(name: String) {
        ToolRuntime.get(this).mcp().remove(name)
        bump()
    }

    // ------------------------------------------------------------------
    // 插件 / 技能
    // ------------------------------------------------------------------

    private fun reloadPlugins() {
        val rt = ToolRuntime.get(this)
        Thread(Runnable {
            val n = rt.reloadPlugins()
            ui.post {
                bump()
                Toast.makeText(this, "已重载 " + rt.plugins().plugins().size
                        + " 个插件 / " + n + " 个工具", Toast.LENGTH_SHORT).show()
            }
        }, "reload-plugins").start()
    }

    private fun reloadSkills() {
        val rt = ToolRuntime.get(this)
        Thread(Runnable {
            val n = rt.reloadSkills()
            ui.post {
                bump()
                Toast.makeText(this, "已重载 $n 个技能", Toast.LENGTH_SHORT).show()
            }
        }, "reload-skills").start()
    }
}

// ---------------------------------------------------------------------------
// UI 组件
// ---------------------------------------------------------------------------

@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(TBgSurface).padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "‹ 返回", color = TAccent, fontSize = 15.sp,
            modifier = Modifier.clickable { onBack() }.padding(end = 12.dp),
        )
        Text("工具与扩展", color = TTextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, color = TTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = TTextDim, fontSize = 11.5.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(TBgSurface)
            .border(1.dp, TDivider, RoundedCornerShape(12.dp))
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) { content() }
    }
}

@Composable
private fun PillButton(
    text: String,
    enabled: Boolean = true,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> TBgElevated
        primary -> TAccent
        else -> TBgElevated
    }
    val fg = when {
        !enabled -> TTextDim
        primary -> Color.White
        else -> TAccent
    }
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(bg)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, color = fg, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SandboxSection(
    installing: Boolean,
    progress: Int,
    stage: String,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    val rt = ToolRuntime.get(androidx.compose.ui.platform.LocalContext.current)
    val sb = rt.sandbox()
    val installed = sb.isInstalled()
    val supported = UbuntuSandbox.isSupported()

    SectionTitle(
        "Ubuntu 沙箱",
        "从 Operit 移植的 proot + Ubuntu 24.04。装上之后 AI 可以真正执行 shell 命令、" +
                "联网装软件、跑脚本。首次安装约 300MB。",
    )
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(9.dp).clip(RoundedCornerShape(50))
                    .background(if (installed) TOkGreen else TTextDim)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    !supported -> "不支持（需 arm64-v8a）"
                    installing -> "安装中 $progress%"
                    installed -> "已安装 · " + UbuntuSandbox.human(sb.diskUsage())
                    else -> "未安装"
                },
                color = TTextPrimary, fontSize = 13.5.sp,
            )
        }
        if (installing && stage.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(stage, color = TTextSecondary, fontSize = 11.5.sp)
        }

        // /sdcard 指向说明 + 授权入口。
        // 不显示这一段的话，用户会以为「沙箱装好了但里面 /sdcard 是空的」是 bug，
        // 实际是 Android 11+ 的分区存储没给权限。
        if (installed) {
            Spacer(Modifier.height(10.dp))
            Text(
                "沙箱内 /sdcard → " + sb.sharedStorageState(),
                color = if (sb.sharedStorageRoot() != null) TTextDim else TErrRed,
                fontSize = 11.5.sp, lineHeight = 16.sp,
            )
            if (sb.sharedStorageRoot() == null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "要让 AI 读写手机里的真实文件（下载、微信目录等），" +
                            "需要授予「所有文件访问」权限。不授权也能用，" +
                            "只是沙箱里的 /sdcard 只能看到 App 自己的目录。",
                    color = TTextDim, fontSize = 11.5.sp, lineHeight = 16.sp,
                )
                Spacer(Modifier.height(10.dp))
                val ctx = androidx.compose.ui.platform.LocalContext.current
                PillButton("授予文件访问权限") { openAllFilesAccessSettings(ctx) }
            }
        }

        // Shizuku 通道。
        //
        // 这不是可选优化，而是沙箱能不能跑起来的开关：
        // untrusted_app 域禁止执行应用私有目录里的二进制，proot 内部
        // execve(rootfs/usr/bin/env) 会 EACCES。拿到 shell 身份后才行。
        Spacer(Modifier.height(10.dp))
        val shzReady = com.superaoh.core.sandbox.ShizukuShell.isReady()
        Text(
            "执行通道：Shizuku · " + com.superaoh.core.sandbox.ShizukuShell.stateText(),
            color = if (shzReady) TOkGreen else TErrRed,
            fontSize = 11.5.sp, lineHeight = 16.sp,
        )
        if (!shzReady) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Android 从 10 起不允许普通应用执行自己目录里的程序（内核层面拦截），" +
                        "proot 因此起不来。授予 Shizuku 权限后，命令会交给 Shizuku 的 " +
                        "shell 身份执行，沙箱才能真正工作。",
                color = TTextDim, fontSize = 11.5.sp, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
            val ctx2 = androidx.compose.ui.platform.LocalContext.current
            PillButton("申请 Shizuku 授权") {
                if (!com.superaoh.core.sandbox.ShizukuShell.isBinderAvailable()) {
                    Toast.makeText(ctx2,
                        "还没连上 Shizuku。请先打开 Shizuku 应用并启动服务，再回到这里。",
                        Toast.LENGTH_LONG).show()
                    com.superaoh.core.sandbox.ShizukuShell.openShizukuApp(ctx2)
                } else if (com.superaoh.core.sandbox.ShizukuShell.hasPermission()) {
                    Toast.makeText(ctx2, "已授权，无需重复申请", Toast.LENGTH_SHORT).show()
                } else {
                    // Shizuku 收到请求后会在它的界面/通知里弹确认，
                    // 用户点允许后 hasPermission() 才会变 true。
                    val sent = com.superaoh.core.sandbox.ShizukuShell.requestPermission(0x5A1)
                    Toast.makeText(ctx2,
                        if (sent) "已发出授权请求，请在 Shizuku 的弹窗里点「允许」"
                        else "请求发送失败，请打开 Shizuku 应用手动授权",
                        Toast.LENGTH_LONG).show()
                    if (!sent) com.superaoh.core.sandbox.ShizukuShell.openShizukuApp(ctx2)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row {
            if (!installed) {
                PillButton(
                    if (installing) "安装中…" else "安装沙箱",
                    enabled = supported && !installing,
                    primary = true,
                ) { onInstall() }
            } else {
                PillButton("卸载", enabled = !installing) { onUninstall() }
            }
        }
    }
}

/**
 * 跳转系统的「所有文件访问」授权页。
 *
 * MANAGE_EXTERNAL_STORAGE 是特殊权限，没有弹窗 API，只能把用户送到设置页。
 * 部分 ROM（尤其国产）会把入口藏起来，所以失败时退回到应用详情页。
 */
private fun openAllFilesAccessSettings(ctx: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT < 30) {
        Toast.makeText(ctx, "当前系统版本无需此权限", Toast.LENGTH_SHORT).show()
        return
    }
    val pkgUri = android.net.Uri.parse("package:" + ctx.packageName)
    val candidates = listOf(
        android.content.Intent(
            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri
        ),
        android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri
        ),
    )
    for (it in candidates) {
        try {
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(it)
            return
        } catch (t: Throwable) {
            // 换下一个
        }
    }
    Toast.makeText(ctx, "无法打开设置页，请手动到「设置 → 应用 → 特殊权限」授权",
        Toast.LENGTH_LONG).show()
}

@Composable
private fun McpSection(
    rev: Int,
    onAdd: () -> Unit,
    onEdit: (McpManager.Server) -> Unit,
    onDelete: (String) -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
) {
    val rt = ToolRuntime.get(androidx.compose.ui.platform.LocalContext.current)
    val servers = rt.mcp().servers()

    SectionTitle(
        "MCP 服务器",
        "接上任意 MCP（Model Context Protocol）服务器，它提供的工具会自动出现在 AI 手里。" +
                "填 URL 即可，支持 Bearer Token 和自定义请求头。",
    )
    Card {
        if (servers.isEmpty()) {
            Text("还没有配置服务器。", color = TTextDim, fontSize = 12.5.sp)
        } else {
            servers.forEachIndexed { i, s ->
                if (i > 0) {
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(TDivider))
                    Spacer(Modifier.height(10.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(
                            when {
                                !s.enabled -> TTextDim
                                s.connected -> TOkGreen
                                else -> TErrRed
                            }
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name, color = TTextPrimary, fontSize = 13.sp)
                        Text(
                            when {
                                !s.enabled -> "已禁用"
                                s.connected -> s.toolCount.toString() + " 个工具"
                                else -> s.lastError ?: "未连接"
                            },
                            color = if (s.connected) TTextSecondary else TErrRed,
                            fontSize = 11.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        "编辑", color = TAccent, fontSize = 12.sp,
                        modifier = Modifier.clickable { onEdit(s) }.padding(6.dp),
                    )
                    Text(
                        "删除", color = TErrRed, fontSize = 12.sp,
                        modifier = Modifier.clickable { onDelete(s.name) }.padding(6.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row {
            PillButton("添加服务器", primary = true) { onAdd() }
            Spacer(Modifier.width(8.dp))
            if (servers.isNotEmpty()) {
                // 一键重连：改完 token 或服务器重启后不用一个个点
                PillButton("全部重连") {
                    servers.filter { it.enabled }.forEach { onConnect(it.name) }
                }
            }
        }
    }
}

@Composable
private fun PluginSection(rev: Int, onReload: () -> Unit) {
    val rt = ToolRuntime.get(androidx.compose.ui.platform.LocalContext.current)
    val list = rt.plugins().plugins()

    SectionTitle(
        "插件",
        "声明式插件：一个目录 + plugin.json，可提供 shell 或 HTTP 工具。" +
                "放到下面这个路径就能被识别。",
    )
    Card {
        Text(
            rt.plugins().root().absolutePath,
            color = TTextSecondary, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(10.dp))
        if (list.isEmpty()) {
            Text("还没有插件。", color = TTextDim, fontSize = 12.5.sp)
        } else {
            list.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.name + if (p.version.isNotEmpty()) "  v" + p.version else "",
                            color = if (p.ok()) TTextPrimary else TErrRed, fontSize = 12.5.sp,
                        )
                        Text(
                            if (p.ok()) p.toolCount.toString() + " 个工具" else p.error ?: "",
                            color = TTextSecondary, fontSize = 11.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        PillButton("重新加载插件") { onReload() }
    }
}

@Composable
private fun SkillSection(rev: Int, onReload: () -> Unit) {
    val rt = ToolRuntime.get(androidx.compose.ui.platform.LocalContext.current)
    val list = rt.skills().skills()

    SectionTitle(
        "技能",
        "技能 = 提示词 + 可选工具。用户说的话命中关键词时，对应的专业知识会自动注入给 AI。",
    )
    Card {
        Text(
            rt.skills().root().absolutePath,
            color = TTextSecondary, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(10.dp))
        if (list.isEmpty()) {
            Text("还没有技能。", color = TTextDim, fontSize = 12.5.sp)
        } else {
            list.forEach { s ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, color = TTextPrimary, fontSize = 12.5.sp)
                        if (s.always) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "常驻", color = TAccent, fontSize = 9.5.sp,
                                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                    .background(TBgElevated).padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                        if (s.toolCount > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text("${s.toolCount} 工具", color = TTextDim, fontSize = 9.5.sp)
                        }
                    }
                    if (s.triggers.isNotEmpty()) {
                        Text(
                            "触发词：" + s.triggers.joinToString(" / "),
                            color = TTextSecondary, fontSize = 10.5.sp,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        PillButton("重新加载技能") { onReload() }
    }
}

@Composable
private fun ToolListSection(rev: Int) {
    val rt = ToolRuntime.get(androidx.compose.ui.platform.LocalContext.current)
    val grouped = rt.registry().grouped()

    SectionTitle(
        "当前可用工具",
        "AI 每一轮都会看到这里的全部工具。数量太多会稀释它的注意力，建议按需开关来源。",
    )
    Card {
        Text(rt.registry().summary(), color = TTextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        for ((src, tools) in grouped) {
            Text(
                "$src  (${tools.size})",
                color = TAccent, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            tools.forEach { t: ToolSpec ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        t.name(), color = TTextPrimary, fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        t.description() ?: "", color = TTextDim, fontSize = 10.5.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun McpEditDialog(
    server: McpManager.Server,
    onDismiss: () -> Unit,
    onSave: (McpManager.Server) -> Unit,
) {
    var name by remember { mutableStateOf(server.name) }
    var url by remember { mutableStateOf(server.url) }
    var token by remember { mutableStateOf(server.apiKey) }
    var headers by remember { mutableStateOf(server.headersRaw) }
    var enabled by remember { mutableStateOf(server.enabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TBgElevated,
        title = {
            Text(
                if (server.name.isEmpty()) "添加 MCP 服务器" else "编辑服务器",
                color = TTextPrimary, fontSize = 16.sp,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Tf("名称", name) { name = it }
                Spacer(Modifier.height(8.dp))
                Tf("URL（例如 https://example.com/mcp）", url) { url = it }
                Spacer(Modifier.height(8.dp))
                Tf("API Key / Token（可空）", token) { token = it }
                Spacer(Modifier.height(8.dp))
                Tf("额外请求头，每行 Key: Value（可空）", headers, single = false) { headers = it }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(18.dp).clip(RoundedCornerShape(4.dp))
                            .background(if (enabled) TAccent else TBgSurface)
                            .clickable { enabled = !enabled },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (enabled) Text("✓", color = Color.White, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("启用", color = TTextPrimary, fontSize = 12.5.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val s = McpManager.Server()
                s.name = name.trim()
                s.url = url.trim()
                s.apiKey = token.trim()
                s.headersRaw = headers
                s.enabled = enabled
                if (s.name.isEmpty() || s.url.isEmpty()) {
                    return@TextButton
                }
                onSave(s)
            }) { Text("保存并连接", color = TAccent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TTextSecondary) }
        },
    )
}

@Composable
private fun Tf(
    label: String,
    value: String,
    single: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp, color = TTextDim) },
        singleLine = single,
        textStyle = androidx.compose.ui.text.TextStyle(
            color = TTextPrimary, fontSize = 12.5.sp,
            fontFamily = if (single) FontFamily.Default else FontFamily.Monospace,
        ),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
    )
}
