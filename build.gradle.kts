// 顶层构建脚本：插件版本统一由 gradle/libs.versions.toml 管理
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // 注意：AGP 9 内置 Kotlin 支持，不再单独 apply org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.serialization) apply false
}
