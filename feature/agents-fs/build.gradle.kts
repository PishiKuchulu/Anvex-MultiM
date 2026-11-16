// Plain JVM module. Its tests need the host RealProjectFileSystem (lives in the Android library core:android, which would pull in the
// NDK build); a copy is kept in src/test and HostCopyUpToDateTest fails if it drifts from the host file.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":feature:agents-core"))
    implementation(project(":core:engine"))
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
    systemProperty("agents.fs.main", layout.projectDirectory.dir("src/main/kotlin").asFile.absolutePath)
    systemProperty("agents.host.realfs", rootProject.file("core/android/src/main/kotlin/com/anvex/core/android/fs/RealProjectFileSystem.kt").absolutePath)
}
