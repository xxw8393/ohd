plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)

    jvm()

    // 与 ohd-platform 保持一致：只承诺 posix 平台（macOS / Linux 的 klib 里有 posix）。
    macosArm64()
    linuxX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // 路径抽象 + PngDecode + inflateRaw：解码这条腿已跨平台
                api(project(":ohd-platform"))
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
        // K/N 共用源集：macOS/Linux 都是 posix
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
}
