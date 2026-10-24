plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.superaoh"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.superaoh"
        minSdk = 24
        // ------------------------------------------------------------------
        // targetSdk 必须是 34 及以上，这不是「追新」，而是 proot 能否工作的
        // 硬性前提。实测（同机 Android 15 / 一加 ColorOS）：
        //
        //   targetSdk = 33  -> 进程落进 u:r:untrusted_app_32 域
        //                      该域对 app 私有目录下的文件没有 execute 权限，
        //                      proot 自己能被 exec（放在 nativeLibraryDir 里），
        //                      但它一旦去执行 rootfs 里的 /usr/bin/env，
        //                      内核就报 EACCES：
        //                        proot error: execve("/usr/bin/env"): Permission denied
        //                      随后 proot 自己的 tmp 目录 chmod/chdir 也全部
        //                      返回 "Function not implemented"，沙箱彻底不可用。
        //
        //   targetSdk = 34  -> 进程落进 u:r:untrusted_app 域，允许从私有目录
        //                      执行代码，proot 正常工作。
        //                      （同一台机器上的 Operit targetSdk=34，域是
        //                        u:r:untrusted_app，proot 跑得很正常，这是对照证据）
        //
        // 所以不要为了「兼容性」把这里降回 33 —— 一降沙箱就废。
        // ------------------------------------------------------------------
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // 复用现有源码目录结构（app/src/main/java 同时放 java 与 kt）
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java")
            res.srcDirs("src/main/res")
            jniLibs.srcDirs("src/main/jniLibs")
            // 沙箱资产：Ubuntu rootfs（64MB xz）+ proot 二进制
            // 来源是 Operit 的 assets，已提取到项目 sandbox/ 目录。
            //
            // 注意：sandbox/assets 里还有一个 rootfs-plain.tar（289MB），
            // 那是 UbuntuSandbox.xzToTar() 在**运行时**由 xz 解出来的中间产物。
            // 它绝不能打进 APK：
            //   1. 代码只读 assets/ubuntu-noble-*.tar.xz 这一个文件
            //      （见 UbuntuSandbox.ROOTFS_ASSET），从不读 rootfs-plain.tar；
            //   2. 打进去会让 APK 白涨 302MB（实测 511MB -> 209MB）；
            //   3. 它的落点是 getExternalFilesDir()，属于运行期缓存，
            //      设备上本来就会自己生成。
            // 所以下面用 ignoreAssetsPattern 把它挡在打包之外。
            assets.srcDirs("src/main/assets", "../sandbox/assets")
        }
    }

    // rootfs 是 xz 压缩包，不能被 aapt 二次压缩
    // （否则解压前还要先去 aapt 那层，白占内存）
    androidResources {
        noCompress += listOf("xz", "tar")
        // rootfs-plain.tar 是运行时生成的中间产物，不打进 APK（省 302MB）。
        // 语法是 aapt 的 ignoreAssets 模式：冒号分隔的文件名 glob。
        ignoreAssetsPattern = "rootfs-plain.tar"
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs += listOf("-nowarn")
    }

    packaging {
        // proot 不是真正的共享库，是改了名的可执行文件。
        //
        // useLegacyPackaging 必须为 true：
        //   = false 时 AGP 把 .so 原样留在 APK 内、运行时直接 mmap，
        //   nativeLibraryDir 目录里根本不会有这个文件，proot 就找不到。
        //   = true 时系统在安装阶段把 lib/arm64-v8a/* 解压到
        //   /data/app/.../lib/arm64/，那才是唯一允许 exec 的地方。
        //
        // keepDebugSymbols 是防止 AGP 调 ndk-strip。proot 是静态链接的
        // 可执行文件，strip 它虽不至于损坏，但没必要冒险。
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libproot.so"
        }
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/versions/**"
            )
        }
    }

    // 使用现有的调试签名
    signingConfigs {
        create("superaoh") {
            storeFile = file("../build-keys/testkey.keystore")
            storePassword = "android"
            keyAlias = "superaoh"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("superaoh")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("superaoh")
        }
    }
}

dependencies {
    // ---------- Compose ----------
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")

    // ---------- 本地 jar ----------
    // 协程由 Compose/activity-compose 传递依赖提供，不能再放本地 coroutines.jar，
    // 否则会出现 Duplicate class _COROUTINE.* 冲突。
    implementation(files("libs/serjson.jar"))
    implementation(files("libs/sercore.jar"))
    implementation(files("libs/markwon-core.jar"))
    implementation(files("libs/commonmark.jar"))
    implementation(files("libs/gdx-game.jar"))
    // xz 解压：Android 的 toybox tar -J 依赖外部 xz 可执行文件（不存在），
    // 所以 rootfs 的 xz 必须由 App 自己解。这个库 151KB，纯 Java 无 native。
    implementation(files("libs/xz.jar"))
    // Shizuku API：提供以 shell(uid 2000) 身份执行命令的能力，
    // 是绕开 untrusted_app 域「不能从应用私有目录 exec 代码」限制的唯一途径。
    // 见 ShizukuShell 的类注释。
    implementation(files("libs/shizuku.jar"))
}