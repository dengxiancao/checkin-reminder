// 职责：Gradle 设置入口，声明插件/依赖仓库与包含的模块（中国区使用阿里云镜像兜底 dl.google.com 不可达）
pluginManagement {
    repositories {
        // 优先走国内镜像，避免 dl.google.com / repo1 在国内被墙导致构建失败
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        // 高德 SDK 发布在 Maven Central；若使用离线包可改为 flatDir / libs
        maven("https://maven.aliyun.com/repository/central")
    }
}

rootProject.name = "CheckinReminder"
include(":app")
