// UNVERIFIED: written against Anvex 4.15.2 sources but never compiled (no Gradle/Android toolchain in the planning environment).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.anvex.agents.runtime"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

ksp {
    // agents.db has its own schema history (ADR-016); snapshots are committed like the host's.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":feature:agents-core"))
    api(project(":feature:agents-fs"))
    implementation(project(":core:model"))
    implementation(project(":core:engine"))
    implementation(project(":core:ai"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
