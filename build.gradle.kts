// 职责：根工程构建脚本，仅声明插件版本（apply false），实际应用在各模块中
plugins {
    // AGP 8.2.2 与 Gradle 8.7 匹配；本机 SDK 仅安装 android-33，故 compileSdk 取 33
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    // KSP 用于 Room 注解处理器（比 KAPT 更快）
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
}
