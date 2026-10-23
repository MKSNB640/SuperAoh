# SuperAoh

> 《文明时代 2 / Age of Civilizations II》Android 平台的工作区管理与数据编辑器。
> 不修改原始 APK，通过工作区 **overlay 覆盖层** 机制安全地加载 mod 与修改游戏数据。

<p align="left">
  <img alt="platform" src="https://img.shields.io/badge/platform-Android%207.0%2B-3DDC84">
  <img alt="kotlin" src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF">
  <img alt="compose" src="https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.09.03-4285F4">
  <img alt="minSdk" src="https://img.shields.io/badge/minSdk-24-blue">
  <img alt="targetSdk" src="https://img.shields.io/badge/targetSdk-34-blue">
</p>

---

## 目录

### 📌 新接手项目请先读

| 文档 | 用途 |
| --- | --- |
| **[HANDOFF.md](HANDOFF.md)** | **交接快照**：当前状态、卡点、下一步动作、数据结构、排查表 |
| [DEVELOPMENT_LOG.md](DEVELOPMENT_LOG.md) | 完整开发过程与踩坑记录（为什么这样做） |

### 项目文档

- [这是什么](#这是什么)
- [核心特性](#核心特性)
- [数据编辑器（重点）](#数据编辑器重点)
  - [文明编辑器](#文明编辑器)
  - [政体编辑器](#政体编辑器)
- [overlay 覆盖层机制](#overlay-覆盖层机制)
- [技术架构](#技术架构)
- [目录结构](#目录结构)
- [构建方式](#构建方式)
  - [方式一：Gradle（推荐，支持 Compose）](#方式一gradle推荐支持-compose)
  - [方式二：build.sh（无 Gradle 快速构建）](#方式二buildsh无-gradle-快速构建)
  - [构建前置资源](#构建前置资源)
- [安装到设备](#安装到设备)
- [测试脚本](#测试脚本)
- [已知限制](#已知限制)
- [待办事项](#待办事项)
- [协作开发约定](#协作开发约定)
- [免责声明](#免责声明)

---

## 这是什么

SuperAoh 是一个 Android 应用，它把《文明时代 2》当作一个**可被管理的工作区**：

- 用户导入游戏 APK 到工作区，应用负责解包、缓存、加载；
- 游戏本体通过 `GameContainerActivity` 启动，libGDX 运行时被整合进本应用；
- 所有对游戏资源的修改都写入工作区 `overlay/assets`，**原始 APK 永远只读**；
- 内置一套独立的**数据编辑器**，不启动游戏本体即可查看/修改文明与政体数据。

项目同时内置了 AI 对话、工具链（apktool / jadx / smali）、Ubuntu 沙箱等辅助能力，
用于在设备上进行逆向与 mod 开发。

---

## 核心特性

| 模块 | 说明 |
| --- | --- |
| 工作区管理 | 多工作区、APK 哈希校验、隔离的 `data` / `overlay` / `patches` 目录 |
| 游戏容器 | `GameContainerActivity` + `GameRuntime`，libGDX 游戏以本应用进程运行 |
| 文件代理 | `GameFilesProxy` 优先读取 overlay，实现「不改 APK 的 mod 加载」 |
| 数据编辑器 | Compose 界面，文明 / 政体两套独立编辑流程 |
| AI 助手 | OpenAI 兼容接口，支持流式输出、多配置档、工具调用 |
| 工具链 | 内置 apktool / smali / baksmali / jadx 的调用封装 |
| 沙箱 | Ubuntu rootfs + proot（可选 Shizuku 提升权限） |
| MCP | 支持外部 MCP Server 接入 |

---

## 数据编辑器（重点）

入口：主界面工作区卡片 →「数据编辑（文明 / 政体）」→ `DataEditorActivity`
包名：`com.superaoh.ui.compose.DataEditorActivity`

**设计原则**：文明与政体是两套**互不混淆**的编辑器。

- 文明编辑器：**极简**，只处理名称、RGB 颜色、Service Ribbon；
- 政体编辑器：**完整表单**，保留全部字段分组参数。

### 文明编辑器

对应游戏资源：

```
assets/game/civilizations/Age_of_Civilizations              索引文件（分号分隔 tag 列表）
assets/game/civilizations/{tag}                             文明对象（Java 原生序列化）
assets/game/languages/civilizations/Bundle.properties       英文名称包
assets/game/languages/civilizations/Bundle_han.properties   中文名称包
```

可编辑内容：

| 项 | 字段 | 说明 |
| --- | --- | --- |
| 名称 | 语言包 `tag = 显示名` | 名称**不在** `civilizations` 目录里，必须改语言包 |
| 颜色 | `iR` / `iG` / `iB` | 0–255 整数 RGB |
| Service Ribbon 标签 | `sr_GameData.sSRTAG` | 徽章标签 |
| Service Ribbon 颜色 | `sr_GameData.lColors[].fR/fG/fB` | 0.0–1.0 浮点颜色 |

技术要点：

- 文明对象是 **Java 原生序列化**格式，通过工作区 `DexClassLoader` 加载游戏类
  `Civilization_GameData3` 后反序列化；
- 索引格式存在版本差异（原版 `;tag1;tag2;`、部分 mod 为 `tag1;tag2;`），
  代码用 `indexHasLeadingSemicolon` 记录原始风格，保存时保持一致；
- mod 数据可能存在**悬空索引项**（索引里有 tag 但 APK 内无对应文件），
  加载时跳过而不是整体失败；
- 新建文明时 tag 使用「时间戳 + 6 位随机」，与游戏生成规则一致。

### 政体编辑器

对应游戏资源：

```
assets/game/GovernmentsV2.json   （优先）
assets/game/Governments.json     （回退）
```

**关键点**：这两个文件**不是标准 JSON**，而是 libGDX 宽松格式：

```
{
    Government: [          // 键名不带引号
        {
            Name: 专制,
            ACCEPTABLE_TAXATION: 0.4,
            CIVILIZE_TECH_LEVEL: 2.0f,   // 数字带 f 后缀
            CAN_BECOME_CIVILIZED: -1,    // -1 表示特殊值
            SomeFlag: true,
        },                     // 允许尾逗号
    ]
}
```

因此项目自带宽松解析器 `GameJson.kt`，负责：

- 解析未加引号的键、
- 解析 `2.0f` / `1.5d` 等数字后缀、
- 容忍尾逗号与注释；
- **写回时输出标准 JSON**（游戏自身可正常读取）。

字段分组：基本信息 / 经济 / 军事 / 行政 / 文明化。

数值范围取自真实数据：

| 字段类型 | 范围 |
| --- | --- |
| 比例字段（`ACCEPTABLE_TAXATION`、`MIN_GOODS`、`MIN_INVESTMENTS`） | 0.0 – 1.0（UI 显示为百分比，写回仍存原始比例） |
| `GOV_GROUP_ID` | 0 – 4 |
| `CAN_BECOME_CIVILIZED` | -1 或 0/1 |
| 颜色 | 0 – 255 |

---

## overlay 覆盖层机制

```
/data/data/com.superaoh/files/workspaces/{workspaceId}/
├── game.apk                 原始游戏 APK（只读，永不修改）
├── overlay/
│   └── assets/
│       └── game/
│           ├── civilizations/
│           │   ├── Age_of_Civilizations
│           │   └── {tag}
│           └── Governments.json
├── data/                    工作区私有数据
├── patches/                 补丁目录
└── asset_cache/             解包缓存
```

读取优先级：**overlay > APK 内 assets**。

`GameFilesProxy` 在游戏读取资源时先查 overlay，命中则返回 overlay 文件。
因此：

- ✅ 修改随时可回滚（删掉 overlay 文件即可）
- ✅ 原始 APK 完整性不受影响，可校验哈希
- ✅ 多个工作区互不干扰

写入使用 `GameDataAccess.atomicWrite()`（临时文件 + 重命名），避免写一半崩溃导致资源损坏。

---

## 技术架构

```
com.superaoh
├── ai/                 LLM 提供方抽象、OpenAI 兼容实现、重试策略、上下文预算
├── container/          游戏容器：GameRuntime / GameFilesProxy / GameBridge / GameInputBridge
├── core/               核心：Workspace、Json、Http、ApkInspector、ChatStore、工具运行时
│   ├── mcp/            MCP 客户端与管理器
│   ├── plugin/         插件管理
│   ├── sandbox/        Ubuntu 沙箱、Shizuku 桥、xz 解压
│   ├── skill/          技能管理
│   ├── tool/           工具注册表与内置工具（工程 / 游戏 / 逆向 / 沙箱）
│   └── toolchain/      apktool / jadx / smali 调用封装
├── ui/
│   ├── chat/           流式会话注册表、WebView 聊天
│   └── compose/        Compose UI（聊天 / 工具 / 数据编辑器）
└── xyz/chouxuewei/mobile_agent/core/    Agent 决策与执行核心
```

数据编辑器相关文件：

| 文件 | 职责 |
| --- | --- |
| `ui/compose/DataEditorActivity.kt` | 编辑器界面（Compose），页面状态与交互 |
| `ui/compose/CivilizationDataRepository.kt` | 文明数据读写（索引 + 序列化对象 + 语言包） |
| `ui/compose/GovernmentDataRepository.kt` | 政体数据读写（宽松 JSON） |
| `ui/compose/GameDataAccess.kt` | 跨 ClassLoader 反射、序列化/反序列化、原子写入 |
| `ui/compose/GameJson.kt` | libGDX 宽松 JSON 解析器 |

### 关于 `GameDataAccess` 的跨 ClassLoader 反射

游戏类由工作区专属的 `DexClassLoader` 加载，与宿主 `PathClassLoader` 不同。
这带来一个必须注意的坑：

> **ART 的 `Class.getMethods()` 会过滤掉声明类对当前类加载器不可见的方法。**

因此反射查找必须：

1. 沿继承链调用 `getDeclaredMethods()`（而不是 `getMethods()`）；
2. 对 `Constructor` / `Method` 显式 `setAccessible(true)`；
3. setter 参数允许 `null`（例如清空可空的 `sr_GameData`）。

代码中对应 `findMethod()` / `declaredMethodsNamed()` / `newInstance()`。

---

## 目录结构

```
SuperAoh项目/
├── app/
│   ├── build.gradle.kts         应用级构建配置
│   ├── libs/                    本地 jar（gdx-game / sercore / serjson / markwon / xz / shizuku）
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── chatui/          聊天 WebView 前端资源
│       │   └── toolchain/       内置逆向工具（见「构建前置资源」，不入库）
│       ├── java/                源码（java + kt 混放）
│       ├── jniLibs/             native 库（proot 等）
│       └── res/
├── build.gradle.kts             根构建脚本
├── settings.gradle.kts
├── gradle.properties            aarch64 关键配置（aapt2 覆盖）
├── build.sh                     无 Gradle 的 javac + D8 构建
├── guild.sh                     Gradle 构建封装（注意拼写是 guild）
├── build-keys/                  签名密钥（不入库）
├── docs/                        截图与文档资源
├── tools/                       测试与探测脚本
├── sandbox/assets/              沙箱 rootfs 与 proot（不入库）
└── work_log.md / change_log.md  开发日志
```

---

## 构建方式

### 方式一：Gradle（推荐，支持 Compose）

Compose 编译器插件只能通过 Gradle（KGP）加载，**改 Compose 界面必须用这种方式**。

```bash
# 环境要求
#   JDK 17+（实测 21 可用）
#   Gradle 8.9
#   Android SDK（compileSdk 34）

export ANDROID_HOME=/opt/android-sdk
export JAVA_HOME=/path/to/jdk

# 快速语法检查（只编译 Kotlin，最快）
./guild.sh check
# 等价于：
/tmp/gradle-download/gradle-8.9/bin/gradle --offline :app:compileDebugKotlin

# 完整构建 debug APK
./guild.sh build
# 等价于：
/tmp/gradle-download/gradle-8.9/bin/gradle --offline :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> **aarch64 设备上的关键配置**：AGP 会下载 x86_64 版 aapt2，在 arm64 上无法执行。
> 因此 `gradle.properties` 必须保留：
> ```
> android.aapt2FromMavenOverride=/usr/bin/aapt2
> ```
> 并确保系统已安装 arm64 版 aapt2（Debian/Ubuntu：`apt install aapt`）。

### 方式二：build.sh（无 Gradle 快速构建）

纯 `javac` + `D8` + `aapt2` + `apksigner` 手动组装，不需要 Gradle，但**不支持 Compose**。

```bash
./build.sh            # 全量构建 release
./build.sh install    # 构建后 adb install
./build.sh clean      # 清理
```

需要的工具：

| 工具 | 路径 | 获取方式 |
| --- | --- | --- |
| android.jar | `/usr/lib/android-sdk/platforms/android-23/android.jar` | `apt install android-sdk-platform-23` |
| aapt2 | `/usr/bin/aapt2` | `apt install aapt` |
| r8 | `/opt/superaoh/tools/r8-8.5.35.jar` | 下载 R8 release |
| kotlinc | `/tmp/kotlinc/kotlinc/bin/kotlinc` | 可选，有则启用 Kotlin 混合编译 |
| zipalign | `/usr/bin/zipalign` | build-tools |
| apksigner | `/usr/bin/apksigner` | `apt install apksigner` |

> ⚠️ **R8 版本坑**：R8 4.0.48 存在致命 bug，遇到 `implements 泛型接口<具体类型>`
> 必抛 `NullPointerException`。**8.5.35 已修复，切勿降级。**

### 构建前置资源

以下资源体积过大，**不纳入 Git 仓库**，需要本地自行准备：

| 路径 | 体积 | 说明 |
| --- | --- | --- |
| `sandbox/assets/rootfs-plain.tar` | ~289 MB | Ubuntu rootfs（沙箱用） |
| `sandbox/assets/ubuntu-noble-aarch64-pd-*.tar.xz` | ~62 MB | 压缩版 rootfs |
| `app/src/main/assets/toolchain/jadx/` | ~122 MB | jadx 反编译工具 |
| `app/src/main/assets/toolchain/apktool.jar` | ~24 MB | apktool |
| `app/src/main/assets/toolchain/smali.jar` | ~5.2 MB | smali 汇编 |
| `app/src/main/assets/toolchain/baksmali.jar` | ~4.3 MB | smali 反汇编 |

**只想构建数据编辑器、不需要沙箱/工具链时**，可以临时移除
`app/build.gradle.kts` 中的 `assets.srcDirs("src/main/assets", "../sandbox/assets")`
中的 `../sandbox/assets`，并在 `assets/toolchain/` 放占位文件，即可显著减小 APK 体积。

---

## 安装到设备

⚠️ **必须经 `/data/local/tmp` 中转**，因为 Package Manager 无法读取共享存储路径（scoped storage）：

```bash
cp app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/sa.apk
pm install -r /data/local/tmp/sa.apk
rm -f /data/local/tmp/sa.apk
```

⚠️ **磁盘空间**：debug APK 约 535 MB（含沙箱 rootfs 与工具链）。
首次安装若报 `INSTALL_FAILED_INSUFFICIENT_STORAGE`，需先清理 `/data/local/tmp` 中旧 APK。

启动：

```bash
am start -n com.superaoh/.ui.MainActivity
# 直接打开数据编辑器
am start -n com.superaoh/.ui.compose.DataEditorActivity
```

---

## 测试脚本

`tools/` 下提供纯 JVM 测试（不需要 Android 设备）：

```bash
# 政体宽松 JSON 解析测试
bash tools/test_gamejson.sh
# 输出：16 个政体、28 字段、往返一致、类型保留正确

# 文明数据链路测试（依赖真实游戏 APK）
bash tools/test_civilizations.sh '/path/to/game.apk'
```

已有验证结论：

- ✅ 政体：16 条记录 / 每条 28 字段，解析→写出→再解析结构完全一致，
  `ACCEPTABLE_TAXATION` 正确解析为 `0.4`（不是 40），`2.0f`、布尔、字符串均正常；
- ✅ 类型保留：整数键仍是 `Int`，比例键仍是 `Double`，`-1` 特殊值可保留；
- ✅ 文明：语言包名称解析、目标行替换、行数不变、其他条目不受影响。

---

## 已知限制

1. **文明编辑器真机流程尚未完全闭环**
   最新一轮反射修复（`getDeclaredMethods()` 沿继承链查找）已编译通过，
   但仍需在真实设备上确认「加载文明 / 新建文明 / 保存」三步全部正常。

2. **overlay 只影响游戏读取，不影响 APK 本身**
   若要分享 mod，需要自行打包 overlay 目录。

3. **政体编辑器依赖真实数据字段**
   新建政体时复制最后一条作为模板以保证字段齐全。若游戏版本新增字段，
   需要同步更新 `GovernmentDataRepository` 的字段表。

4. **mod 数据可能自带缺陷**
   测试样本中 3267 个文明索引项有 397 个悬空引用，这是 mod 自身问题，
   编辑器会跳过并继续，不会崩溃。

5. **沙箱需要 root 或 Shizuku**
   Ubuntu 沙箱在 `targetSdk < 34` 时会因 SELinux 域限制无法执行 rootfs 内程序。
   `targetSdk = 34` 是硬性要求，**不要为了「兼容性」降回 33**。

6. **APK 体积大（约 535 MB）**
   主要来自沙箱 rootfs 与内置逆向工具链。

---

## 待办事项

- [ ] 真机验证文明编辑器完整流程（加载 / 新建 / 修改 / 保存）
- [ ] 真机验证政体编辑器所有分组字段
- [ ] 增加设备级自动化测试（依赖 workspace APK + DexClassLoader + 序列化对象）
- [ ] 减小 APK 体积：沙箱与工具链改为首次启动时按需下载
- [ ] 支持文明删除与 Service Ribbon 多徽章管理
- [ ] 导出 / 导入 overlay 为可分享的 mod 包

---

## 协作开发约定

本项目使用 **Operit + Monkey Code** 协同开发，请遵守以下约定：

### 提交前

1. 改任何文件前，先用 `grep_code` / `grep_context` 定位并理解上下文，不要盲改；
2. 修改后必须能通过 `./guild.sh check`（Compose 相关）；
3. 涉及数据格式的改动，必须跑 `tools/test_gamejson.sh` 或 `tools/test_civilizations.sh`。

### 代码风格

- Kotlin 遵循 `kotlin.code.style=official`；
- 源码目录 `app/src/main/java` 同时存放 `.java` 与 `.kt`，按包名组织，不另建 `kotlin/` 目录；
- 关键的非显然实现（尤其是逆向得出的格式细节、反射坑）**必须写注释说明原因**，
  而不是只写「做了什么」——这类知识一旦丢失极难重新推导。

### 不要提交

`.gitignore` 已排除，请勿强行 `git add -f`：

- `build-keys/`（签名密钥）
- `app/src/main/assets/toolchain/`（大体积工具）
- `sandbox/assets/*.tar*`（rootfs）
- `移植功能区/`（反编译工作区）
- `app/build/`、`.gradle/`、`.kotlin/`（构建产物）
- 任何 token / 密钥 / 日志

### 凭据管理

**不要在代码、文档或命令行中硬编码 token。**
使用环境变量或 Git credential helper：

```bash
export GITHUB_TOKEN="$(cat ~/.config/gh/token)"
git remote set-url origin "https://${GITHUB_TOKEN}@github.com/USER/REPO.git"
```

---

## 免责声明

- 本项目仅用于**单机游戏 mod 制作与学习研究**；
- 不包含游戏本体，需用户自行拥有正版；
- 请勿用于商业分发或绕过游戏授权验证；
- 修改数据导致的存档损坏、游戏异常，由使用者自行承担风险，建议操作前备份存档。
