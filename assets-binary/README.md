# assets-binary —— 分块存放的超大二进制资源

## 为什么存在这个目录

GitHub 对**单个文件**有 **100 MB 硬限制**（超过会被直接拒收，不是警告）。
本项目有一个构建必需资源超过了这个限制：

| 文件 | 大小 | 目标位置 | 处理方式 |
| --- | --- | --- | --- |
| `jadx.jar` | 122 MB | `app/src/main/assets/toolchain/jadx/jadx.jar` | 拆成 3 块存放在此 |

其余大资源都在 100 MB 以下，**已直接提交在原位置**，不需要经过这里：

| 文件 | 大小 | 位置 |
| --- | --- | --- |
| `ubuntu-noble-aarch64-pd-v4.18.0.tar.xz` | 62 MB | `sandbox/assets/` |
| `apktool.jar` | 24 MB | `app/src/main/assets/toolchain/` |
| `smali.jar` | 5.2 MB | `app/src/main/assets/toolchain/` |
| `baksmali.jar` | 4.3 MB | `app/src/main/assets/toolchain/` |

> 注意：`sandbox/assets/rootfs-plain.tar`（289 MB）**故意不提交也不打包**。
> 它是 `UbuntuSandbox.xzToTar()` 在设备上运行时由 xz 解压生成的中间产物，
> 代码从不读取它。`app/build.gradle.kts` 里用 `ignoreAssetsPattern` 把它挡住，
> 避免 APK 白涨 302 MB。

---

## 分块清单

| 分块文件 | 大小 | SHA256 |
| --- | --- | --- |
| `jadx.jar.part-aa` | 60 MB | `17072833deab709f745dd22b55e9e7f469f0563f31bb7e16ff623f96cc1173b4` |
| `jadx.jar.part-ab` | 60 MB | `20fa5929eddc809d4c9c263ec07de45265e83723963282cda589bcf9fb4ba453` |
| `jadx.jar.part-ac` | 1.1 MB | `4776198dee90f0b91d01b3f15f0fcf98eebd8aa8459a56f4b59c9dbcd5173909` |

拼接后的完整文件必须满足：

```
长度   : 126,980,139 字节
SHA256 : dc717e51c08d31a66357933c8d4896755d7c4939745e03aefd02427041f9aa53
```

---

## 如何还原

### 方式一：用仓库自带脚本（推荐）

```bash
# 在仓库根目录执行
./tools/restore-binaries.sh

# 只校验不写入
./tools/restore-binaries.sh --check
```

脚本会：

1. 按字典序拼接 `jadx.jar.part-*`；
2. 用 SHA256 校验结果；
3. 写回 `app/src/main/assets/toolchain/jadx/jadx.jar`；
4. 顺带检查其他构建必需资源是否齐全。

脚本是幂等的：目标文件已存在且校验通过就跳过。

### 方式二：手动拼

```bash
cd assets-binary
cat jadx.jar.part-aa \
    jadx.jar.part-ab \
    jadx.jar.part-ac > ../app/src/main/assets/toolchain/jadx/jadx.jar

# 校验
sha256sum ../app/src/main/assets/toolchain/jadx/jadx.jar
# 应输出 dc717e51c08d31a66357933c8d4896755d7c4939745e03aefd02427041f9aa53
```

### Windows 用户

```powershell
cd assets-binary
cmd /c "copy /b jadx.jar.part-aa+jadx.jar.part-ab+jadx.jar.part-ac ..\app\src\main\assets\toolchain\jadx\jadx.jar"
```

或用 PowerShell：

```powershell
$parts = Get-ChildItem jadx.jar.part-* | Sort-Object Name
$out = "..\app\src\main\assets\toolchain\jadx\jadx.jar"
$fs = [System.IO.File]::Create($out)
foreach ($p in $parts) {
    $bytes = [System.IO.File]::ReadAllBytes($p.FullName)
    $fs.Write($bytes, 0, $bytes.Length)
}
$fs.Close()
```

---

## 分块是怎么生成的

供参考，**不需要重做**（除非要更新 jadx 版本）：

```bash
# 每块 60MB，生成 jadx.jar.part-aa / part-ab / part-ac
split -b 60M -d -a 2 app/src/main/assets/toolchain/jadx/jadx.jar jadx.jar.part-
```

注意用 `-a 2` 保证后缀是两位（`aa` / `ab` / `ac`），字典序与拼接顺序一致。

---

## 为什么不直接删掉 jadx

`jadx` 是应用内置的 APK 反编译工具（`ToolchainManager` 会把它复制到
`/sdcard/Android/data/<pkg>/files/toolchain/jadx/jadx.jar` 供沙箱内的
Java 调用）。删掉它会导致「反编译 APK」功能不可用，所以必须保留。

如果你的使用场景完全不需要反编译功能，可以跳过还原步骤，
但 `ToolchainManager.isInstalled()` 会因为找不到 `jadx/jadx.jar` 而返回 false。