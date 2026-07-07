// Top-level build file where you can add configuration options common to all sub-projects/modules.

buildscript {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }

    dependencies {
        classpath(libs.google.services)
        classpath(libs.kotlin.gradle.plugin)
        classpath(libs.ksp.gradle.plugin)
        classpath(libs.navigation.safe.args.gradle.plugin)
        classpath(libs.kover.plugin)
        classpath(libs.shot.plugin)

        // NOTE: Do not place your application dependencies here; they belong
        // in the individual module build.gradle files
    }
}

plugins {
    alias(libs.plugins.android.application).apply(false)
    alias(libs.plugins.android.library).apply(false)
    alias(libs.plugins.android.kotlin.multiplatform.library).apply(false)
    alias(libs.plugins.buildConfig).apply(false)
    alias(libs.plugins.compose).apply(false)
    alias(libs.plugins.compose.compiler).apply(false)
    alias(libs.plugins.gradle.play.publisher).apply(false)
    alias(libs.plugins.kotlin.android).apply(false)
    alias(libs.plugins.kotlinx.kover).apply(false)
    alias(libs.plugins.kotlinx.serialization).apply(false)
    alias(libs.plugins.ksp).apply(false)
    alias(libs.plugins.ktlint).apply(false)
    alias(libs.plugins.maven.publish).apply(false)
    alias(libs.plugins.multiplatform).apply(false)
    alias(libs.plugins.spmForKmp).apply(false)
}

val androidxJunitVersion = libs.versions.junitKtx.get()

allprojects {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "androidx.test.ext" && requested.name == "junit") {
                useVersion(androidxJunitVersion)
                because("Keep AndroidX test JUnit aligned with the version catalog when Compose UI test declares an older transitive version")
            }
        }
    }
    afterEvaluate {
        tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask> {
            exclude("**/generated/**")
            exclude("**/build/**")
        }
        tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintFormatTask> {
            exclude("**/generated/**")
            exclude("**/build/**")
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
