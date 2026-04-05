plugins {
    alias(libs.plugins.multiplatform)

    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
}

apply(plugin = "shot")

// Apply shared version calculation script
apply(from = rootProject.file("gradle/version.gradle.kts"))

// Helper functions to access the shared version calculations
fun calculateVersionCode(): Int = (extra["calculateVersionCode"] as () -> Int).invoke()

fun calculateVersionName(): String = (extra["calculateVersionName"] as () -> String).invoke()

fun getGitHash(): String = (extra["getGitHash"] as () -> String).invoke()

kotlin {
    androidTarget {
        publishLibraryVariants(
            "release",
        )
        compilations.all {
            compileTaskProvider {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                    freeCompilerArgs.add("-Xjdk-release=${JavaVersion.VERSION_21}")
                }
            }
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        iosX64(),
    ).forEach { target ->
        target.compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    allWarningsAsErrors.set(false)
                }
            }
        }
        target.binaries.framework {
            baseName = "walletSDKUi"
            isStatic = true
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)

                implementation(project(":wallet-sdk-core"))

                implementation(libs.napier)

                implementation(compose.animation)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)
                implementation(compose.foundation)
                implementation(compose.material)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.runtime)
                implementation(libs.bitcoin.kmp)
                implementation(libs.coil.compose)
                implementation(libs.coil.network.ktor)
                implementation(project.dependencies.platform(libs.koin.bom))
                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(libs.koin.compose.viewmodel)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.logging)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.kotlinx.serialization)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.utils)
                implementation(libs.room.runtime)
                implementation(libs.sqlite.bundled)
                implementation(libs.kotlinx.datetime)
                implementation(libs.webview.multiplatform.mobile)
                implementation(libs.compose.webview.multiplatform)
                implementation(libs.qrkit)
                implementation(libs.navigation.compose)
                implementation(libs.datastore)
                implementation(libs.datastore.preferences)
                implementation(libs.room.runtime)
                implementation(libs.sqlite.bundled)
                implementation(libs.bignum)
                implementation(libs.compottie)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        androidMain {
            dependencies {
                implementation(libs.androidx.activityCompose)
                implementation(libs.androidx.compose.foundation)
                implementation(libs.androidx.lifecycle.runtime.compose)
                implementation(libs.androidx.lifecycle.viewmodel.compose)
                implementation(libs.androidx.lifecycle.viewmodel.savedstate)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.kotlinfixture)
                implementation(libs.ktor.client.android)
                implementation(libs.ktor.client.okhttp)
                implementation(compose.uiTooling)
                implementation(compose.components.uiToolingPreview)
                implementation(libs.koin.android)
                implementation(libs.androidx.credentials)
                implementation(libs.biometric)
                implementation(libs.bcprov.jdk18on)
                implementation(libs.androidx.credentials)
                implementation(libs.algosdk)
                implementation(libs.algorand.go.mobile)

                implementation(libs.algorand.foundation.crypto)
                implementation(libs.algorand.foundation.provider)

                implementation(libs.play.services.fido)
                implementation(libs.kotlinx.coroutines.play.services)
                implementation(libs.okhttp)
                implementation(libs.okhttp.logging.interceptor)
                implementation(libs.coroutines.okhttp)
                implementation(libs.socketio.client)
                implementation(libs.stream.webrtc.android)
                implementation(libs.uuid.generator)
                implementation(libs.sol4k)

                // Solana Mobile Seed Vault
                implementation(libs.seedvault.wallet.sdk)

                // CameraX for video streaming (Android actual implementation)
                implementation(libs.camerax.core)
                implementation(libs.camerax.camera2)
                implementation(libs.camerax.lifecycle)
                implementation(libs.camerax.view)
            }
        }

        androidInstrumentedTest {
            dependencies {
                implementation(compose.uiTooling)
                implementation(compose.material3)
                implementation(compose.foundation)
                implementation(compose.runtime)
                implementation(compose.components.resources)
                implementation(libs.navigation.compose)
                implementation(libs.junit)
                implementation(libs.androidx.junit)
                implementation(libs.compose.ui.testManifest)
                implementation(libs.compose.ui.test.junit4)
                implementation(libs.androidx.uiautomator)
            }
        }

        iosMain {
            dependencies {}
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
    namespace = "com.michaeltchuang.walletsdk.ui"
    compileSdk = 36

    packagingOptions {
        exclude("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }

    defaultConfig {
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        testApplicationId = "com.michaeltchuang.walletsdk.ui.test"
        testInstrumentationRunner = "com.karumi.shot.ShotTestRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }


    sourceSets["main"].res.srcDirs("src/commonMain/composeResources", "src/androidMain/res")
    sourceSets["main"].resources.srcDirs("src/commonMain/composeResources")

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

val generateBuildInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/kotlin")
    val outputFile = outputDir.get().file("com/michaeltchuang/walletsdk/ui/BuildInfo.kt").asFile

    // Heuristic to determine build variant (platform-agnostic).
    // Checks for "debug" in Gradle task names or Konan target names (iOS, native).
    val taskNames =
        gradle.startParameter.taskNames
            .joinToString(" ")
            .lowercase()
    val projectProperties = gradle.startParameter.projectProperties
    val buildTypeProp = (project.findProperty("buildType") as? String)?.lowercase()
    val konanTargetProp =
        (project.findProperty("konanTarget") as? String)?.lowercase() // For iOS/native

    // Find "debug" for Android/iOS/native multiplatform
    val debug =
        taskNames.contains("debug") ||
            (
                projectProperties["android.injected.build.variant"]?.lowercase()?.contains("debug")
                    ?: false
            ) ||
            (projectProperties["buildType"]?.lowercase()?.contains("debug") ?: false) ||
            (buildTypeProp?.contains("debug") ?: false) ||
            (konanTargetProp?.contains("debug") ?: false)

    outputs.dir(outputDir)
    outputs.upToDateWhen { false } // Always regenerate to get latest git hash

    doLast {
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package com.michaeltchuang.walletsdk.ui

            object BuildInfo {
                const val VERSION_CODE = ${calculateVersionCode()}
                const val VERSION_NAME = "${calculateVersionName()}"
                const val GIT_HASH = "${getGitHash()}"

                // Note: For iOS, this reflects the Gradle build config.
                // iOS debug status is determined by Xcode build configuration.
                // Use isDebugBuild() function for runtime debug detection.
                const val DEBUG = $debug
            }

            """.trimIndent(),
        )
    }
}

kotlin.sourceSets.commonMain {
    kotlin.srcDir(generateBuildInfo.map { it.outputs.files.singleFile })
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateBuildInfo)
}

afterEvaluate {
    tasks.withType<Jar>().configureEach {
        if (name.contains("SourcesJar", ignoreCase = true)) {
            dependsOn(generateBuildInfo)
        }
    }
}

mavenPublishing {
    coordinates(
        "com.michaeltchuang.algokit.walletsdk",
        "wallet-sdk-ui",
        System.getenv("VERSION_TAG") ?: "0.0.1",
    )

    pom {
        name.set("AlgoKit Wallet SDK UI")
        description.set("UI layer for AlgoKit Wallet SDK")
        url.set("https://github.com/michaeltchuangllc/algokit-walletsdk-kmp")

        licenses {
            license {
                name.set("GPL3.0 License")
                url.set("https://opensource.org/licenses/GPL-3.0")
            }
        }

        developers {
            developer {
                id.set("michaeltchuang")
                name.set("Michael T Chuang")
                email.set("hello@michaeltchuang.com")
                organization.set("Michael T Chuang LLC")
                organizationUrl.set("https://github.com/michaeltchuangllc")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/michaeltchuangllc/algokit-walletsdk-kmp.git")
            developerConnection.set("scm:git:ssh://github.com/michaeltchuangllc/algokit-walletsdk-kmp.git")
            url.set("https://github.com/michaeltchuangllc/algokit-walletsdk-kmp")
        }
    }
}

// Task to copy screenshots to GitHub Pages directory
val copyPhoneScreenshots by tasks.registering(Copy::class) {
    description =
        "Copy screenshots to /docs/screenshots/phone for GitHub Pages (mirrors and deletes old " +
        "files)"
    group = "documentation"

    from("screenshots/debug") {
        include("*.png")
    }
    into(rootProject.file("docs/screenshots/phone"))

    doFirst {
        rootProject.file("docs/screenshots/phone").mkdirs()
    }
}

val copyTabletScreenshots by tasks.registering(Copy::class) {
    description =
        "Copy screenshots to /docs/screenshots/tablet for GitHub Pages (mirrors and deletes old " +
        "files)"
    group = "documentation"

    from("screenshots/debug") {
        include("*.png")
    }
    into(rootProject.file("docs/screenshots/tablet"))

    doFirst {
        rootProject.file("docs/screenshots/tablet").mkdirs()
    }
}
