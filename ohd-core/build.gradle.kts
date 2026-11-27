plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)

    jvm()

    // 与 ohd-platform / ohd-image 保持一致：只承诺 posix 平台
    macosArm64()
    linuxX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // 宿主平台抽象（路径/文件系统/环境变量/进程/摘要）
                api(project(":ohd-platform"))
                // 图像：解码/算法已跨平台，编码在 JVM 侧
                api(project(":ohd-image"))
                // 只用 JsonElement 构建/解析，不启用 serialization 编译器插件
                api(libs.kotlinx.serialization.json)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmMain by getting
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.junit.jupiter)
            }
        }
        val nativeMain by creating {
            dependsOn(commonMain)
        }
        val macosArm64Main by getting {
            dependsOn(nativeMain)
        }
        val linuxX64Main by getting {
            dependsOn(nativeMain)
        }
        val nativeTest by creating {
            dependsOn(commonTest)
        }
        val macosArm64Test by getting {
            dependsOn(nativeTest)
        }
        val linuxX64Test by getting {
            dependsOn(nativeTest)
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging { events("failed") }
}
