import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    // this needs to be first in list
    alias(libs.plugins.multiplatform)

    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.room)
    id("kotlin-parcelize")
    alias(libs.plugins.spmForKmp)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kotlinx.kover)
}

kotlin {

    androidTarget {
        publishLibraryVariants("release")
        compilations.all {
            compileTaskProvider {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_21)
                    freeCompilerArgs.add("-Xjdk-release=${JavaVersion.VERSION_21}")
                }
            }
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_4)
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        iosX64(),
    ).forEach { iosTarget ->
        iosTarget.compilations {
            val main by getting {
                cinterops {
                    create("AlgorandIosSdk")
                }
            }
        }
    }

    swiftPackageConfig {
        create("AlgorandIosSdk") {
            customPackageSourcePath = "${layout.projectDirectory.asFile.path}/src/iosMain/swift"
            minIos = "16.2"
            dependency {
                remotePackageVersion(
                    url = uri("https://github.com/Electric-Coin-Company/MnemonicSwift.git"),
                    products = {
                        add("MnemonicSwift")
                    },
                    version = "2.2.5",
                )
                remotePackageBranch(
                    url = uri("https://github.com/algorandecosystem/falcon-signatures-mobile.git"),
                    products = {
                        add("FalconMobileSDK")
                    },
                    branch = "main",
                )
                remotePackageBranch(
                    url = uri("https://github.com/algorandecosystem/algokit-core-swift.git"),
                    products = {
                        add("AlgoKitTransact")
                        add("AlgoKitCrypto")
                        add("AlgoKitComposer")
                        add("AlgoKitUtils")
                    },
                    branch = "main",
                )
            }
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.algokit.composer)
            implementation(libs.algokit.crypto)
            implementation(libs.algokit.transact)

            implementation(libs.algosdk)
            implementation(libs.algorand.falcon)
            implementation(libs.androidx.credentials)
            implementation(libs.biometric)

            // toml files don't support aar files yet
            implementation("net.java.dev.jna:jna:5.17.0@aar")
            implementation(libs.kotlin.bip39)
            implementation(libs.bcprov.jdk18on)
            implementation(libs.androidx.activityCompose)
            implementation(libs.androidx.compose.foundation)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.viewmodel.savedstate)
            implementation(libs.javax.inject)
            implementation(libs.koin.android)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.kotlinfixture)
            implementation(libs.ktor.client.android)
            implementation(libs.ktor.client.okhttp)

            implementation(libs.algorand.foundation.crypto)
            implementation(libs.algorand.foundation.provider)
            implementation(libs.p256)

            // Solana Mobile Seed Vault
            implementation(libs.seedvault.wallet.sdk)

            implementation(libs.socketio.client)
            implementation(libs.stream.webrtc.android)
            implementation(libs.qrcode.kotlin)
            implementation(libs.uuid.generator)
            implementation(libs.play.services.fido)
            implementation(libs.mlkit.barcode.scanning.common)
            implementation(libs.mlkit.camera)
            implementation(libs.socketio.client)
            implementation(libs.stream.webrtc.android)
            implementation(libs.jackson.annotations)
            implementation(libs.jackson.dataformat.msgpack)
            implementation(libs.jackson.dataformat.cbor)
            implementation(libs.json.kotlin.schema)

            // JCS (RFC 8785) JSON canonicalization — required for challenge HMAC input and
            // `request` field serialization per draft-algorand-charge.
            implementation(libs.json.canonicalization)
        }
        commonMain.dependencies {
            api(libs.napier)

            implementation(compose.runtime)
            implementation(libs.coil.compose)
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
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.junit)
                implementation(libs.mockk)
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

android {
    namespace = "com.michaeltchuang.walletsdk.core"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()
    defaultConfig {
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        // enables a Compose tooling support in the AndroidStudio
        compose = true
    }

    packaging {
        resources {
            // Exclude duplicate files from multiple dependencies
            excludes.addAll(
                listOf(
                    "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                    "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                    "META-INF/DEPENDENCIES",
                    "META-INF/LICENSE",
                    "META-INF/LICENSE.txt",
                    "META-INF/license.txt",
                    "META-INF/NOTICE",
                    "META-INF/NOTICE.txt",
                    "META-INF/notice.txt",
                    "META-INF/ASL2.0",
                    "META-INF/*.kotlin_module",
                ),
            )
            // Pick first occurrence of duplicate files
            pickFirsts.add("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
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

room {
    schemaDirectory("$projectDir/schemas")
}

configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
    }
}

tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask>().configureEach {
    exclude {
        it.file.path.contains("generated") || it.file.path.contains("build/")
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

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosX64", libs.room.compiler)
}

mavenPublishing {
    coordinates(
        "com.michaeltchuang.algokit.walletsdk",
        "wallet-sdk-core",
        System.getenv("VERSION_TAG") ?: "0.0.1",
    )

    pom {
        name.set("AlgoKit Wallet SDK Core")
        description.set("A headless wallet engine for Algorand")
        url.set("https://github.com/michaeltchuangllc/algokit-walletsdk-kmp")

        licenses {
            license {
                name.set("GPL-3.0 License")
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

kover {
    reports {
        filters {
            excludes {
                // Exclude generated code
                classes("*.BuildConfig")
                classes("*_Factory")
                classes("*_MembersInjector")
                classes("*Dagger*")
                classes("*_Provide*")

                // Exclude Android/Compose generated code
                classes("*.databinding.*")
                classes("*.di.*")
                classes("androidx.compose.*")

                // Exclude test utilities
                packages("*.test")
                packages("*.testing")
            }
        }
    }
}
