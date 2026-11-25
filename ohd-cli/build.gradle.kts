plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":ohd-core"))
    implementation(project(":ohd-image"))
    // core 用 implementation 声明，不传递到编译类路径，这里必须重复声明
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}

application {
    applicationName = "ohd"
    mainClass.set("ohd.cli.MainKt")
}

tasks.named<Jar>("jar") {
    manifest { attributes["Implementation-Version"] = project.version }
}
