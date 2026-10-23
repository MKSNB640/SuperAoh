# 开发日志（数据编辑器专题）

> 记录数据编辑器从需求到当前的完整开发过程、每次排查的结论与踩过的坑。
> 与 `HANDOFF.md`（当前状态快照）配合阅读。
> 与 `work_log.md` / `change_log.md`（工具自动写入）互为补充。

---

## 阶段一：需求澄清与架构定型

### 需求

用户要在 SuperAoh 里增加**独立数据编辑器**，不启动游戏本体，
直接读取工作区 APK 数据，修改后写入 overlay。

### 关键转折

最初设计把文明和政体做成同一套复杂表单，用户明确纠正：

> 「文明编辑器只是简单的名称、RGB 颜色和服务徽章编辑，政体编辑器才是完整参数表单。」

于是拆成两套完全独立的编辑流程：

- 文明 → 极简（名称 / RGB / Service Ribbon）
- 政体 → 完整分组表单（基本信息 / 经济 / 军事 / 行政 / 文明化）

### 产出的模块

```
DataEditorActivity.kt              编辑器 UI（Compose，703 行）
CivilizationDataRepository.kt      文明数据读写
GovernmentDataRepository.kt        政体数据读写
GameDataAccess.kt                  跨 ClassLoader 反射 / 序列化 / 原子写
GameJson.kt                        libGDX 宽松 JSON 解析
```

入口注册在 `AndroidManifest.xml`，主页工作区卡片加入口。

---

## 阶段二：政体数据格式 —— 发现「不是标准 JSON」

### 问题

用项目原有的 `Json.parse` 解析 `Governments.json` 直接失败。

### 排查

查看真实文件内容后发现是 **libGDX 宽松格式**：

```
{
    Government: [                       // 键名不带引号
        {
            Name: 专制,
            ACCEPTABLE_TAXATION: 0.4,
            CIVILIZE_TECH_LEVEL: 2.0f,  // 数字带 f 后缀
            CAN_BECOME_CIVILIZED: -1,   // -1 特殊值
        },                              // 尾逗号
    ]
}
```

### 解决

新写 `GameJson.kt`：

- 解析未加引号的键
- 解析 `2.0f` / `1.5d` 等数字后缀
- 容忍尾逗号与注释
- **写回时输出标准 JSON**（游戏可正常读取标准 JSON）

### 验证结果（`tools/test_gamejson.sh`）

```
[ok] 测试数量 = 16
[ok] 首条字段数 = 28
[ok] ACCEPTABLE_TAXATION = 0.4（不是 40）
[ok] 往返一致
[ok] 整数键仍是 Int
[ok] 比例键仍是 Double
[ok] -1 特殊值能保住
全部通过
```

### 字段范围修正

根据真实数据修正了 UI 的取值上限，此前范围过窄/错误：

| 字段 | 修正 |
| --- | --- |
| `ACCEPTABLE_TAXATION` / `MIN_GOODS` / `MIN_INVESTMENTS` | 是 0–1 比例，不是 0–100 |
| `GOV_GROUP_ID` | 实际出现 0–4 |
| `CAN_BECOME_CIVILIZED` | 支持 -1 特殊值 |
| `CIVILIZE_TECH_LEVEL` / `AVAILABLE_SINCE_AGE_ID` | 取消了原本错误的上限 |

> UI 只在**展示层**把比例乘 100 显示成百分比，写回仍存原始比例。

### 测试脚本踩的坑

1. `kotlinc` 只把 `.java` 当类型来源，**不产出 Java 的 class**，
   因此 `Json.java` 必须单独用 `javac` 编译；
2. 测试文件在独立模块中**无法访问 `internal` 的 `GameJson`**，
   需要用 `-Xfriend-paths` 指向主产物。

---

## 阶段三：文明数据结构 —— 名称位置的重大修正

### 最初的错误假设

以为文明名称存在 `civilizations_informations/{tag}_NM` 之类的文件里。

### 实际排查过程

```
1. ls civilizations_informations              → 5484 个条目，但不是 _NM 格式
2. hexdump civilizations/aaa                  → 看到 iB/iG/iR=255, sCivTag="aaa",
                                                sr_GameData.sSRTAG="1", 3 个徽章颜色
                                                → 确认这是序列化对象，名称不在这里
3. hexdump civilizations_informations/aaa     → 内容就是 "aa"，2 字节
                                                → 这不是名称存储
4. 在反编译源码里搜 _NM / CivName             → 无匹配
5. 检查 languages/civilizations/Bundle*.properties
   → 找到！格式为 "aaa = 测试"
```

### 确认的结构

```
assets/game/civilizations/Age_of_Civilizations               索引（分号分隔 tag）
assets/game/civilizations/{tag}                              文明对象（Java 原生序列化）
assets/game/languages/civilizations/Bundle.properties        英文名称
assets/game/languages/civilizations/Bundle_han.properties    中文名称
```

### 修正

重写文明仓库的名称读取与保存逻辑：

- 读：优先 `Bundle_han.properties`，回退 `Bundle.properties`
- 写：写 overlay 副本，**逐行替换**目标 tag 行
- 保留文件名（`nameFileName`），因为不同版本可能是 `Bundle_han` 或 `Bundle`

### 语言包写入的坑

最初用 `readLines()` 再 `joinToString("\n")`，导致**末尾换行丢失、行数变化**。
改为 `split('\n')` 处理后修复。验证结果：目标行替换成功、行数不变、其他条目不受影响。

### `Civilization_GameData3` 字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `iR` / `iG` / `iB` | int | 0–255 RGB |
| `sCivTag` | String | 文明 tag |
| `sr_GameData` | Object | Service Ribbon，**可为 null** |

Service Ribbon：

| 字段 | 说明 |
| --- | --- |
| `sSRTAG` | 徽章标签 |
| `lColors` | 颜色列表，每项 `fR`/`fG`/`fB`，**0.0–1.0 浮点** |

---

## 阶段四：文明索引格式兼容与真实 mod 数据

### 发现的版本差异

| 版本 | 索引格式 |
| --- | --- |
| 原版 | `;tag1;tag2;`（开头有分号） |
| 部分 mod | `tag1;tag2;`（无开头分号） |

两者都能被游戏按分号切分，但**保存时必须保持原风格**，否则可能引起兼容问题。

### 解决

新增 `indexHasLeadingSemicolon` 记录原始风格，保存时按原样生成。

### 真实 mod 数据的额外发现

某个真实 mod 的索引：

- 包含**中文文明 tag**
- 3267 个索引项中有 **397 个悬空引用**（索引里有 tag，APK 内无对应文件）

这是 **mod 自身的数据缺陷**，不是编辑器 bug。

### 处理策略

加载时**跳过**失败项，继续加载其余文明，而不是让整个编辑器失败。
同时收集前几条失败原因，全部失败时才抛出带 tag 的诊断错误
（避免用户只看到「0 个文明」却不知道原因）。

---

## 阶段五：反射与 ClassLoader —— 最顽固的问题

这一阶段踩了三次坑，每次都以为修好了，真机又报错。

### 第一次报错：`0 个文明` + `setSCivTag` 报错

现象：设备上点击「新建文明」，弹出 `Civilization_GameData3.setSCivTag` 相关错误；
之前加载文明时显示「已读取 0 个文明」。

**首次尝试**：加 `setAccessible(true)`。

推测原因是 DexClassLoader 加载的类对主程序 ClassLoader 不可见，
反射调用会做可见性检查。

**但错误仍然存在。**

### 第二次尝试：改用 `newInstance()`

把直接 `getDeclaredConstructor().newInstance()` 改为封装方法 `GameDataAccess.newInstance()`，
统一处理 `setAccessible`；同时让 setter 支持传 `null`（清空可空的 `sr_GameData`）。

**错误仍然存在。**

### 第三次：找到真正根因

重新读 `GameDataAccess.set()` 代码，发现问题在：

```kotlin
value.javaClass.methods     // ← 这里！
```

> **ART 的 `Class.getMethods()` 会过滤掉声明类对当前类加载器不可见的方法。**

在「工作区 `DexClassLoader` 加载游戏类 + 宿主 `PathClassLoader` 执行代码」的跨 ClassLoader 场景下，
`getMethods()` 返回的列表里**没有**目标 setter，于是抛 `NoSuchMethodException`。

**最终修复**：

```kotlin
// 新增：沿继承链用 getDeclaredMethods 查找
private fun declaredMethodsNamed(clazz: Class<*>, name: String): List<Method> {
    val out = mutableListOf<Method>()
    var c: Class<*>? = clazz
    while (c != null) {
        out += c.declaredMethods.filter { it.name == name }
        c = c.superclass
    }
    return out
}

private fun findMethod(clazz: Class<*>, name: String): Method? =
    declaredMethodsNamed(clazz, name).firstOrNull()?.also { it.isAccessible = true }
```

同时：

- 对 `Constructor` 也显式 `setAccessible(true)`
- setter 参数允许 `null`
- 加载失败不再静默，收集诊断信息

### 修复状态

✅ 编译通过（`BUILD SUCCESSFUL`）
❌ **最终 APK 安装 + 真机三步验证尚未完成** ← 当前卡点

### 教训

1. 跨 ClassLoader 反射**永远不要用 `getMethods()`**；
2. 不要静默吞异常，否则「0 个文明」这种错误无从排查；
3. 真机报错要看**完整堆栈**，只看最后一行会误导。

---

## 阶段六：构建与部署

### 构建环境特殊性

在 Android 设备的 Ubuntu proot 中构建，有几个坑：

| 坑 | 解决 |
| --- | --- |
| AGP 下载 x86_64 aapt2 无法执行 | `gradle.properties` 设 `android.aapt2FromMavenOverride=/usr/bin/aapt2` |
| 无 Gradle wrapper | 用 `/tmp/gradle-download/gradle-8.9/bin/gradle` |
| `BUILD SUCCESSFUL` 但 `pm` 不可用 | `pm` 要用 `super_admin:shell` 而非 `terminal`（proot 里没有 pm） |

### 安装问题

首次安装失败：`INSTALL_FAILED_INSUFFICIENT_STORAGE`，
APK 约 535 MB（含沙箱 rootfs + 工具链）。

清理 `/data/local/tmp` 中多个旧 APK 后，磁盘从 1.6G 恢复到 4.2G，安装成功。

### 为什么必须经 `/data/local/tmp` 中转

Package Manager 是 `system_server` 进程，**读不到 `/storage/emulated/0`**（scoped storage 限制）。

```bash
cp app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/sa.apk
pm install -r /data/local/tmp/sa.apk
```

---

## 阶段七：上传 GitHub 与交接文档

### 项目体积盘点

| 目录 | 体积 | 处理 |
| --- | --- | --- |
| `app/build` | 1.7 GB | 排除 |
| `sandbox` | 351 MB | 排除 `.tar*`（rootfs） |
| `app/src/main/assets/toolchain` | 155 MB | 排除 |
| `app/src/main/java` | 1.4 MB | ✅ 提交 |
| `app/libs` | 4.3 MB | ✅ 提交 |
| `docs` | 2.7 MB | ✅ 提交 |
| `移植功能区` | 58 MB | 排除（反编译工作区） |

最终提交 **157 个文件，约 12 MB**。

### 遇到的问题：FUSE 不支持 git 对象库

`/storage/emulated/0` 是 FUSE 挂载，`git add` 直接报：

```
unable to write file .git/objects/xx/xxxx: No such file or directory
```

**解决**：把 Git 对象库放 Linux 私有目录，工作树仍指向项目：

```bash
export GIT_DIR=/root/gh/superaoh.git
export GIT_WORK_TREE='/storage/emulated/0/SuperAoh项目'
```

### 遇到的问题：push 被拒（non-fast-forward）

第二次提交意外成为**根提交**（没有父提交），导致远程 HEAD 无法快进。

**诊断方法**：

```bash
git cat-file -p HEAD | grep -c '^parent'   # 返回 0 说明没有父提交
git merge-base --is-ancestor origin/main HEAD && echo yes || echo no
```

**解决**：

```bash
git reset --soft 0256e84    # 软重置到远程 HEAD
git commit -m '...'         # 重新提交，这次带上了父提交
git push origin main
```

结果：`0256e84..c496a86 main -> main` ✅

### 已完成的文档

| 文件 | 内容 |
| --- | --- |
| `README.md` | 项目介绍、特性、数据编辑器说明、构建、安装、测试、限制、协作约定 |
| `HANDOFF.md` | 交接快照：当前状态、卡点、下一步动作、数据结构、代码地图、排查表 |
| `DEVELOPMENT_LOG.md` | 本文件，完整开发过程与踩坑记录 |
| `.gitignore` | 排除构建产物、密钥、大体积资源、反编译目录 |

---

## 待续

下一个接手者请优先完成：

1. 文明编辑器真机验证（加载 / 新建 / 保存）
2. 政体编辑器真机验证
3. 补充端到端自动化测试

详见 `HANDOFF.md` §3.2 与 §10。
