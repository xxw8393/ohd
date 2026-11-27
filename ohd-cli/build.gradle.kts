plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)

    jvm()

    // 原生入口：产出独立可执行（无 JVM 启动税）
    macosArm64 { binaries { executable { baseName = "ohd"; entryPoint = "ohd.cli.main" } } }
    linuxX64 { binaries { executable { baseName = "ohd"; entryPoint = "ohd.cli.main" } } }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":ohd-core"))
                implementation(project(":ohd-image"))
                api(project(":ohd-platform"))
                // core 用 implementation 声明，不传递到编译类路径
                implementation(libs.kotlinx.serialization.json)
            }
        }
        val commonTest by getting { dependencies { implementation(kotlin("test")) } }
        val jvmMain by getting
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.junit.jupiter)
            }
        }
        val nativeMain by creating { dependsOn(commonMain) }
        val macosArm64Main by getting { dependsOn(nativeMain) }
        val linuxX64Main by getting { dependsOn(nativeMain) }
        val nativeTest by creating { dependsOn(commonTest) }
        val macosArm64Test by getting { dependsOn(nativeTest) }
        val linuxX64Test by getting { dependsOn(nativeTest) }
    }
}

tasks.withType<Test> { useJUnitPlatform() }

/*
 * 分发链路兼容层。
 *
 * 原来用 `application` 插件产出 `build/install/ohd/{bin,lib}`，而 `dist/ohd`
 * 与 `dist/package.sh` 直接读那个路径。`application` 与 KMP 插件不兼容，
 * 所以这里手工复刻同样的目录结构，**不改打包脚本**。
 */
val jvmRuntimeClasspath = configurations.named("jvmRuntimeClasspath")

val installDist by tasks.registering(Sync::class) {
    dependsOn("jvmJar")
    into(layout.buildDirectory.dir("install/ohd"))
    into("lib") {
        // 运行时依赖全部平铺进 lib/，与 dist/ohd 的 `-cp "$LIB/*"` 约定一致
        from(jvmRuntimeClasspath)
        from(tasks.named("jvmJar"))
    }
    into("bin") {
        // 只放一个说明文件：真正的启动器是 dist/ohd 脚本
        from(resources.text.fromString("#!/bin/sh\nexec \"\$(dirname \"\$0\")/../ohd\" \"\$@\"\n")) {
            rename { "ohd" }
        }
    }
    doLast {
        // 启动器需要可执行位
        file("${layout.buildDirectory.get()}/install/ohd/bin/ohd").setExecutable(true)
    }
}

tasks.named("build") { dependsOn(installDist) }
