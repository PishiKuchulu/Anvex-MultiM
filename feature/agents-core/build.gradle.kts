plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // ADR-009: agents-core depends on nothing but the Kotlin standard library.
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
    // ArchitectureGuardTest scans these sources.
    systemProperty("agents.core.main", layout.projectDirectory.dir("src/main/kotlin").asFile.absolutePath)
}
