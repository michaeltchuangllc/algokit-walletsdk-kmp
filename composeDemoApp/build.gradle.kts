import com.android.build.api.dsl.ManagedVirtualDevice
import org.gradle.kotlin.dsl.implementation
import org.jetbrains.compose.ExperimentalComposeLibrary
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree
import java.util.Properties

plugins {
    // has to be first in plugins list
    alias(libs.plugins.multiplatform)

    alias(libs.plugins.android.application)
    alias(libs.plugins.buildConfig)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.gradle.play.publisher)
}

apply(from = rootProject.file("gradle/version.gradle.kts"))

// Helper functions to access the shared version calculations
fun calculateVersionCode(): Int = (extra["calculateVersionCode"] as () -> Int).invoke()

fun calculateVersionName(): String = (extra["calculateVersionName"] as () -> String).invoke()

fun getGitHash(): String = (extra["getGitHash"] as () -> String).invoke()

kotlin {
    androidTarget {
        compilations.all {
            compileTaskProvider {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_21)
                    freeCompilerArgs.add("-Xjdk-release=${JavaVersion.VERSION_21}")
                }
            }
        }
        // https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-test.html
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        instrumentedTestVariant {
            sourceSetTree.set(KotlinSourceSetTree.test)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        iosX64(),
    ).forEach {
        it.binaries.framework {
            baseName = "composeDemoApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.animation)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(compose.foundation)
            implementation(compose.material)
            implementation(compose.material3)
            implementation(compose.runtime)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.core.viewmodel)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.koin.compose.viewmodel.navigation)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.navigation.compose)
            implementation(libs.compottie)
//            implementation(libs.walletsdk.ui)
//            implementation(libs.walletsdk.core)
            implementation(project(":wallet-sdk-core"))
            implementation(project(":wallet-sdk-ui"))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            @OptIn(ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(compose.uiTooling)
            implementation(libs.androidx.activityCompose)
            implementation(libs.koin.android)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.datastore.preferences)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

configurations.all {
    resolutionStrategy {
        // Force a single version of BouncyCastle to avoid conflicts
        force("org.bouncycastle:bcprov-jdk18on:1.83")

        // Exclude the older version
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
}

android {
    namespace = "com.michaeltchuang.walletsdk.demo"
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
    sourceSets["main"].apply {
        manifest.srcFile("src/androidMain/AndroidManifest.xml")
        res.srcDirs("src/androidMain/res")
    }
    // https://developer.android.com/studio/test/gradle-managed-devices
    @Suppress("UnstableApiUsage")
    testOptions {
        managedDevices.devices {
            maybeCreate<ManagedVirtualDevice>("pixel5").apply {
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
    buildFeatures {
        compose = true
    }

    // Add Android Instrumented Test dependencies
    dependencies {
        androidTestImplementation(libs.compose.ui.test.junit4)
        debugImplementation(libs.compose.ui.testManifest)
    }

    // Load signing config from system environment first, then local.properties
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
                    // Exclude duplicate files from BouncyCastle and other dependencies
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
            // Pick first occurrence of duplicate files
            pickFirsts += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    lint {
        // Disable problematic rules for KMP
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

        // Continue on lint errors instead of failing the build
        abortOnError = false

        // Skip lint for release builds to speed up builds
        checkReleaseBuilds = false

        // Only run lint on changed files
        checkDependencies = false
    }
}

buildConfig {
    // BuildConfig configuration here.
    // https://github.com/gmazzo/gradle-buildconfig-plugin#usage-in-kts
}

play {
    serviceAccountCredentials.set(file("../service-account.json"))
    track.set("internal")
    defaultToAppBundles.set(true)
    commit.set(false)
}
