// SuperAoh 根构建脚本
// 采用 AGP + Kotlin Compose 插件，用于编译 Compose UI。
// 现有的 build.sh（纯 javac/D8）继续保留，用于快速无 Gradle 构建。
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}