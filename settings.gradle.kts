// 注意：pluginManagement 必须是 settings 的第一个块，否则 Gradle 直接报错。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        mavenCentral()
    }
}

// 国内镜像优先，同事机器首次构建不卡（实测 aliyun 0.13s vs central 0.52s）
dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
    }
}

rootProject.name = "ohd"

include(":ohd-core", ":ohd-image", ":ohd-cli", ":ohd-platform")
