import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.gradle.play.publisher)
}
apply(from = rootProject.file("gradle/version.gradle.kts"))

fun calculateVersionCode(): Int = (extra["calculateVersionCode"] as () -> Int).invoke()

fun calculateVersionName(): String = (extra["calculateVersionName"] as () -> String).invoke()

android {
    namespace = "com.michaeltchuang.walletsdk.demo.android"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()

        applicationId = "com.michaeltchuang.walletsdk.demo"
        versionCode = calculateVersionCode()
        versionName = calculateVersionName()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        aidl = true
        compose = true
    }

    @Suppress("UnstableApiUsage")
    testOptions {
        managedDevices.localDevices {
            create("pixel5") {
                device = "Pixel 5"
                apiLevel = 34
                systemImageSource = "aosp"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { stream -> localProperties.load(stream) }
    }

    val keystorePassword =
        System.getenv("KEYSTORE_PASSWORD")
            ?: localProperties.getProperty("keystore.password")
    val keystoreKeyAlias =
        System.getenv("KEY_ALIAS")
            ?: localProperties.getProperty("key.alias")
    val keystoreKeyPassword =
        System.getenv("KEY_PASSWORD")
            ?: localProperties.getProperty("key.password")

    val keystoreFile =
        when {
            file("../../keystore.jks").exists() -> file("../../keystore.jks")
            rootProject.file("keystore.jks").exists() -> rootProject.file("keystore.jks")
            else -> null
        }

    signingConfigs {
        create("release") {
            storeFile = keystoreFile
            storePassword = keystorePassword
            keyAlias = keystoreKeyAlias
            keyPassword = keystoreKeyPassword
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

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
                    "lib/libnarcissus-macos-64.dylib",
                    "lib/libnarcissus-win-32.dll",
                    "lib/libnarcissus-win-64.dll",
                    "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                    "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
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

    lint {
        disable.addAll(
            listOf(
                "NullSafeMutableLiveData",
                "UnusedResources",
                "MissingTranslation",
                "Instantiatable",
                "InvalidPackage",
                "TypographyFractions",
                "TypographyQuotes",
                "TrustAllX509TrustManager",
                "UseTomlInstead",
                "AndroidGradlePluginVersion",
                "GradleDependency",
            ),
        )
        abortOnError = false
        checkReleaseBuilds = false
        checkDependencies = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

play {
    serviceAccountCredentials.set(file("../service-account.json"))
    track.set("internal")
    defaultToAppBundles.set(true)
}

dependencies {
    implementation(project(":sharedDemoApp"))
    implementation(project(":wallet-sdk-ui"))
    implementation(project(":wallet-sdk-core"))

    implementation(project.dependencies.platform(libs.koin.bom))
    implementation(libs.androidx.activityCompose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.koin.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.datastore.preferences)
    implementation(libs.lifecycle.common.java8)
    implementation(libs.seedvault.wallet.sdk)

    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.uiautomator)
    debugImplementation(libs.compose.ui.testManifest)
}
