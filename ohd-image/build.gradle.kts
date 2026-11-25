plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

kotlin {
    jvmToolchain(21)
}

// M0 只用 JDK 自带 ImageIO（零 native、三平台同源）。
// M2 再按需引入 Skiko 引擎（--engine skia），届时在此加 platform runtime 依赖。
dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}
