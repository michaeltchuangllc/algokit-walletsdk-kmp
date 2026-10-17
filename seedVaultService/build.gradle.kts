import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.room)
}

android {
    namespace = "com.michaeltchuang.seedvault.service"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "com.michaeltchuang.seedvault.service"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        versionCode = 1
        versionName = "0.0.1"
    }

    buildFeatures {
        aidl = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Same duplicate META-INF files as androidDemoApp, pulled in through wallet-sdk-core
    packaging {
        resources {
            excludes +=
                listOf(
                    "META-INF/DEPENDENCIES.md",
                    "META-INF/NOTICE.md",
                    "META-INF/LICENSE.md",
                    "META-INF/LICENSE.txt",
                    "META-INF/NOTICE.txt",
                    "META-INF/ASL2.0.md",
                    "META-INF/DEPENDENCIES",
                    "META-INF/LICENSE",
                    "META-INF/license.txt",
                    "META-INF/NOTICE",
                    "META-INF/notice.txt",
                    "META-INF/ASL2.0",
                    "META-INF/*.kotlin_module",
                )
            pickFirsts += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    // Falcon-1024 seed phrases and Android Keystore encryption
    implementation(project(":wallet-sdk-core"))
    implementation(project.dependencies.platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose.viewmodel)

    // JSON returned over AIDL
    implementation(libs.kotlinx.serialization.json)

    // Seed storage
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activityCompose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
