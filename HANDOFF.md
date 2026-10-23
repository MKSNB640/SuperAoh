# 交接文档（HANDOFF）

> 面向接手本项目的 AI / 开发者。读完本文即可知道**当前状态、改到哪一步、下一步做什么、哪些坑已经踩过**。
>
> 最后更新：项目首次上传 GitHub 时

---

## 1. 一句话现状

数据编辑器的**数据层已验证可靠**，**UI 层已完成**，但**文明编辑器的真机流程尚未最终确认**。

---

## 2. 已完成且已验证

| 项 | 状态 | 验证方式 |
| --- | --- | --- |
| 政体宽松 JSON 解析（`GameJson.kt`） | ✅ 已验证 | `tools/test_gamejson.sh` 输出「全部通过」 |
| 政体往返写入 | ✅ 已验证 | 解析→写出→再解析，16 条 / 28 字段完全一致 |
| 政体字段类型保留 | ✅ 已验证 | `Int` 保持 `Int`，比例保持 `Double`，`-1` 特殊值可保留 |
| 文明语言包名称读写 | ✅ 已验证 | 逐行替换，行数不变，其他条目不受影响 |
| 文明索引格式兼容 | ✅ 已验证 | 兼容 `;tag1;tag2;` 与 `tag1;tag2;` 两种风格 |
| Compose UI（文明列表 / 政体表单） | ✅ 编译通过 | `assembleDebug` BUILD SUCCESSFUL |
| overlay 写入（原子写） | ✅ 代码就绪 | `GameDataAccess.atomicWrite()` |
| README / 交接文档 | ✅ 已完成 | 本文件 + `README.md` |

测试输出实录：

```
== 宽松解析: Governments.json
[ok] 测试数量 = 16
[ok] 首条字段数 = 28
[ok] ACCEPTABLE_TAXATION = 0.4（不是 40）
[ok] 往返一致
[ok] 整数键仍是 Int
[ok] 比例键仍是 Double
[ok] -1 特殊值能保住
全部通过
```

---

## 3. 当前卡点（最重要）

### 3.1 文明编辑器真机报错

用户最后一次真机反馈的报错指向 `Civilization_GameData3.setSCivTag`。

**已定位根因**：

> 游戏类由工作区 `DexClassLoader` 加载，与宿主 `PathClassLoader` 不同。
> **ART 的 `Class.getMethods()` 会过滤掉声明类对当前类加载器不可见的方法**，
> 导致反射找不到 setter，抛 `NoSuchMethodException`。

**已完成的修复**（`GameDataAccess.kt`）：

1. 新增 `findMethod()` / `declaredMethodsNamed()`，**沿继承链调用 `getDeclaredMethods()`**；
2. 对 `Constructor` / `Method` 显式 `setAccessible(true)`；
3. 新增 `newInstance()`，构造器也走 `setAccessible`；
4. `set()` 允许参数为 `null`（支持清空可空的 `sr_GameData`）；
5. 加载阶段不再静默吞异常，改为收集失败 tag 与真实异常，
   全失败时抛出带 tag 的诊断信息（不再只显示「0 个文明」）。

**修复状态**：✅ 已编译通过（BUILD SUCCESSFUL），
❌ **尚未完成最终 APK 安装 + 真机三步验证**。

### 3.2 下一步要做的具体动作

```bash
# 1. 构建
/tmp/gradle-download/gradle-8.9/bin/gradle --offline :app:assembleDebug

# 2. 安装（必须经 /data/local/tmp 中转，scoped storage 限制）
cp app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/sa.apk
pm install -r /data/local/tmp/sa.apk
rm -f /data/local/tmp/sa.apk

# 3. 打开数据编辑器
am start -n com.superaoh/.ui.compose.DataEditorActivity
```

然后依次验证：

- [ ] 文明列表能正确显示数量（不再「0 个文明」）
- [ ] 名称、RGB、Service Ribbon 能正确读出
- [ ] 「新建文明」不报错
- [ ] 保存后重进编辑器，修改仍在（overlay 生效）
- [ ] 启动游戏，修改在游戏内可见

**若仍然报错**：把 `GameDataAccess` 的失败诊断信息完整贴出来——
现在的代码会带上 tag 与真实异常类型/消息，可以直接定位。

---

## 4. 关键数据结构（逆向结论，勿重复推导）

### 4.1 文明

```
assets/game/civilizations/Age_of_Civilizations        索引（分号分隔）
assets/game/civilizations/{tag}                       文明对象（Java 原生序列化）
assets/game/languages/civilizations/Bundle.properties      名称
assets/game/languages/civilizations/Bundle_han.properties  中文名称
```

> ⚠️ **名称不在 `civilizations` 目录里！**
> 早期误以为在 `{tag}_NM` 文件，实际已统一收进语言包，格式为 `tag = 显示名`。

`Civilization_GameData3` 字段：

| 字段 | 说明 |
| --- | --- |
| `iR` / `iG` / `iB` | 0–255 整数 RGB |
| `sCivTag` | 文明 tag |
| `sr_GameData` | Service Ribbon 对象，**可为 null** |

Service Ribbon 对象：

| 字段 | 说明 |
| --- | --- |
| `sSRTAG` | 徽章标签 |
| `lColors` | 颜色列表，每项 `fR` / `fG` / `fB`，**0.0–1.0 浮点** |

> `civilizations_informations/` 目录里放的是别的东西（如 `aaa` 内容就是 `aa`），**不是名称**。

### 4.2 政体

```
assets/game/GovernmentsV2.json   优先
assets/game/Governments.json     回退
```

**不是标准 JSON！** 是 libGDX 宽松格式：

- 键名不带引号
- 数字可带 `f` 后缀（`2.0f`）
- 允许尾逗号
- 允许注释

实测数据：16 个政体，每条 28 字段。

关键字段范围：

| 字段 | 范围 |
| --- | --- |
| `ACCEPTABLE_TAXATION` / `MIN_GOODS` / `MIN_INVESTMENTS` | 0.0–1.0（比例，UI 显示 ×100） |
| `GOV_GROUP_ID` | 0–4 |
| `CAN_BECOME_CIVILIZED` | -1 或 0/1 |
| 颜色字段 | 0–255 |

---

## 5. 代码地图

| 文件 | 职责 | 改动风险 |
| --- | --- | --- |
| `ui/compose/GameDataAccess.kt` | **最核心**：DexClassLoader、反射、序列化、原子写 | 🔴 高，改错全盘失效 |
| `ui/compose/GameJson.kt` | 宽松 JSON 解析 | 🟡 中，改后必须跑测试 |
| `ui/compose/CivilizationDataRepository.kt` | 文明读写 | 🟡 中 |
| `ui/compose/GovernmentDataRepository.kt` | 政体读写 | 🟡 中 |
| `ui/compose/DataEditorActivity.kt` | 编辑器 UI（703 行） | 🟢 低，纯界面 |
| `core/Workspace.java` | 工作区路径（`overlayAssetsDir` 等） | 🟡 中 |
| `container/GameFilesProxy.java` | overlay 读取优先级 | 🔴 高，影响游戏加载 |

### 反射必须遵守的三条铁律

```kotlin
// ❌ 错误：跨 ClassLoader 会过滤掉目标方法
clazz.getMethods().first { it.name == "setSCivTag" }

// ✅ 正确：沿继承链用 getDeclaredMethods，并 setAccessible
var c: Class<*>? = clazz
while (c != null) {
    c.declaredMethods.firstOrNull { it.name == name }?.let {
        it.isAccessible = true
        return it
    }
    c = c.superclass
}
```

---

## 6. 构建

### ⚠️ 环境特殊性（很重要）

本项目当前是在 **Android 设备上的 Ubuntu proot 环境**里构建的，
不是常规 x86_64 PC。有以下特殊点：

1. **aapt2 必须是 arm64 版**
   `gradle.properties` 里必须有：
   ```
   android.aapt2FromMavenOverride=/usr/bin/aapt2
   ```
   否则 AGP 下载的 x86_64 aapt2 无法执行。

2. **Gradle 路径**是 `/tmp/gradle-download/gradle-8.9/bin/gradle`（不是 `./gradlew`，本项目无 wrapper）。

3. **离线构建**：加 `--offline`，依赖已缓存。

4. **APK 体积大**（约 535 MB），安装前确认 `/data` 剩余空间 > 1 GB。

### 命令

```bash
# 快速 Kotlin 语法检查（最快反馈）
/tmp/gradle-download/gradle-8.9/bin/gradle --offline :app:compileDebugKotlin

# 完整构建
/tmp/gradle-download/gradle-8.9/bin/gradle --offline :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 或者用封装脚本
./guild.sh check    # 只编译 Kotlin
./guild.sh build    # 完整构建
```

### 在普通 PC 上构建

需要自行准备：

- `app/src/main/assets/toolchain/`（jadx 122MB / apktool 24MB / smali / baksmali）
- `sandbox/assets/*.tar*`（rootfs 289MB + 62MB）
- `build-keys/`（签名密钥，需自己生成）

**只做数据编辑器开发时**，可以注释掉 `app/build.gradle.kts` 里
`assets.srcDirs("src/main/assets", "../sandbox/assets")` 的第二项，
大幅减小 APK 体积，加快构建。

---

## 7. Git 注意事项

### ⚠️ 当前环境的特殊问题

`/storage/emulated/0` 是 FUSE 挂载，**不支持 git 写入对象库**（会报
`unable to write file .git/objects/...: No such file or directory`）。

**解决方案**（已在使用）：

```bash
export GIT_DIR=/root/gh/superaoh.git          # 对象库放 Linux 私有目录
export GIT_WORK_TREE='/storage/emulated/0/SuperAoh项目'
```

在**普通 PC 上克隆则完全正常**，不需要这个 hack：

```bash
git clone https://github.com/MKSNB640/SuperAoh.git
```

### 提交范围

`.gitignore` 已排除以下内容，**不要 `git add -f` 强加**：

- `build-keys/`（签名密钥）
- `app/src/main/assets/toolchain/`（122MB+ 工具链）
- `sandbox/assets/*.tar*`（rootfs）
- `移植功能区/`（反编译工作区）
- `app/build/`、`.gradle/`、`.kotlin/`、`*.class`
- `local.properties`（含本机 SDK 路径）

### 远程仓库

```
https://github.com/MKSNB640/SuperAoh
```

> 🔒 原始 PAT 已在对话中暴露，**请勿继续使用**。
> 应改为 `gh auth login` 或环境变量方式。

---

## 8. 用户明确的需求边界

这些是用户**反复强调**过的，改代码时不要违背：

1. **文明编辑器必须极简**
   只有：名称、RGB、Service Ribbon。不要加别的字段。

2. **政体编辑器是另一套功能**
   按截图保留完整分组：基本信息 / 经济 / 军事 / 行政 / 文明化。

3. **不能修改原始 APK**
   所有修改只能写 overlay。

4. **不启动游戏本体也能编辑**
   数据编辑器是独立 Activity。

5. **UI 风格**
   深色基底 + 抬升面板 + 黄绿强调色（`Accent`），与主页一致。

---

## 9. 常见问题排查

| 现象 | 原因 | 解决 |
| --- | --- | --- |
| 「0 个文明」 | 反序列化异常被静默吞掉 | 现在会显示真实异常，看诊断信息 |
| `NoSuchMethodException: setSCivTag` | 用了 `getMethods()` | 改 `getDeclaredMethods()` 沿继承链 |
| `AAPT2 Daemon startup failed` | x86_64 aapt2 | 设 `android.aapt2FromMavenOverride` |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | APK 535MB | 清 `/data/local/tmp` |
| `pm: command not found` | 在 proot 里执行 | 用 `super_admin:shell` 而非 `terminal` |
| 政体解析失败 | 用了标准 JSON 解析器 | 必须用 `GameJson.parse()` |
| 文明索引写回后游戏读不到 | 索引风格变了 | 保留 `indexHasLeadingSemicolon` |
| gradle 找不到 | 无 wrapper | 用 `/tmp/gradle-download/gradle-8.9/bin/gradle` |

---

## 10. 建议的下一步优先级

1. **【最高】完成文明编辑器真机验证**（见 §3.2）
2. 政体编辑器真机验证（分组字段逐个确认）
3. 补设备级自动化测试（真正依赖 workspace APK 的端到端）
4. 减小 APK 体积（沙箱/工具链改按需下载）
5. 文明删除、多 Service Ribbon 支持
6. overlay 导出为可分享 mod 包

---

## 11. 给接手 AI 的建议

- **改任何文件前先读代码**，本项目有大量「为什么这样做」的注释，
  尤其是 `GameDataAccess.kt` 和 `GameJson.kt`，里面写的是逆向得出的结论，
  不要凭常识推翻。
- **不要凭猜测改数据格式**。拿真实 APK 验证：
  `tools/test_gamejson.sh` / `tools/test_civilizations.sh`。
- **每次改完必须编译**：`./guild.sh check`，别攒一堆再编。
- 涉及 overlay 路径时，注意 `Workspace.overlayAssetsDir()`
  返回的是 `workspaces/{id}/overlay/assets`，
  写入时路径要相对于 `assets/`，不要重复加 `assets/`。
- 真机报错优先看**完整堆栈**，不要只看最后一行。

---

**祝顺利。所有踩过的坑都记在上面了，别再踩一遍。**