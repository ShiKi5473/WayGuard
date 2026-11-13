plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.wayguard.sync.MainKt")
}

// Relative paths in --args (data/…, build/…) resolve against the repository root.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
    // Chinese report lines must not be mangled by the platform console encoding (Windows).
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

dependencies {
    implementation(project(":guard-core"))
    testImplementation("junit:junit:4.13.2")
}
