plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // 图像标注（shot --grid/--boxes）需要解码/编码；ohd-image 不反向依赖 core，无环
    implementation(project(":ohd-image"))
    // 只用 JsonElement 构建/解析，不启用 serialization 编译器插件（少一个构建面）
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed") }
}
