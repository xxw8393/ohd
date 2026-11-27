plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)

    jvm()

    // 只承诺 posix 平台：macOS / Linux 的 klib 里有 posix，Windows(mingw) 没有。
    // Windows 原生入口留待后用 windows klib 单独实现，在此之前 Windows 走 JVM 入口（行为不变）。
    macosArm64()
    linuxX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // 协程：KMP 上替代 Thread 的统一手段（K/N 没有 Thread）
                api(libs.kotlinx.coroutines.core)
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
        // K/N 共用源集：macOS/Linux 都是 posix，实现可以共用
        val nativeMain by creating {
            dependsOn(commonMain)
        }
    }
}

// 手动建的 nativeMain 不会自动被各 native target 继承，需显式连上
listOf("macosArm64", "linuxX64").forEach { target ->
    kotlin.targets.findByName(target)?.let { t ->
        val main = t.compilations.findByName("main")
        val ss = main?.defaultSourceSet
        ss?.dependsOn(kotlin.sourceSets.getByName("nativeMain"))
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

/**
 * 性能基准用 release 优化过的测试二进制。
 *
 * K/N 默认把 test 二进制编成 **debug（不优化）**。拿它和 JIT 预热后的 JVM 比，
 * 结论会系统性偏向 JVM——这正是「KMP 比 JVM 慢」这类结论最常见的造假来源。
 *
 * 只在显式传 `-Pbench=true` 时打开，避免拖慢日常 `gradlew test`。
 */
if (project.hasProperty("bench")) {
    kotlin.targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.withType<org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable>().configureEach {
            optimized = true
        }
    }
}
