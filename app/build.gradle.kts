plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val dataBaseUrl = providers.gradleProperty("wayguard.dataBaseUrl").get()
val githubRepo = providers.gradleProperty("wayguard.githubRepo").get()
val appVersionCode = providers.gradleProperty("wayguard.versionCode").orNull?.toIntOrNull() ?: 1
val appVersionName = providers.gradleProperty("wayguard.versionName").orNull?.takeIf { it.isNotBlank() } ?: "0.1.0"

android {
    namespace = "com.wayguard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wayguard"
        minSdk = 31
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "DATA_BASE_URL", "\"$dataBaseUrl\"")
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
    }

    val signingStoreFile = System.getenv("SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }
    val signingStorePassword = System.getenv("SIGNING_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
    val signingKeyAlias = System.getenv("SIGNING_KEY_ALIAS")?.takeIf { it.isNotBlank() }
    val signingKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
    val hasReleaseSigning = signingStoreFile != null &&
        signingStorePassword != null &&
        signingKeyAlias != null &&
        signingKeyPassword != null

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(signingStoreFile)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Ship the latest published dataset (cameras.json + meta.json) as the first-run
    // snapshot; the sync worker replaces it with newer downloads at runtime.
    sourceSets["main"].assets.srcDir(rootProject.file("data/published"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val wayMapVersion: String by project
    implementation(project(":guard-core"))
    implementation("com.github.ShiKi5473.WayMap:waymap:$wayMapVersion")

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
}
