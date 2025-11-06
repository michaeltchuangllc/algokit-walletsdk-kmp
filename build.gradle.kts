// Top-level build file where you can add configuration options common to all sub-projects/modules.

buildscript {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }

    dependencies {
        classpath(libs.agp)
        classpath(libs.firebase.crashlytics.gradle)
        classpath(libs.google.services)
        classpath(libs.kotlin.gradle.plugin)
        classpath(libs.ksp.gradle.plugin)
        classpath(libs.navigation.safe.args.gradle.plugin)
        classpath(libs.firebase.perf.plugin)
        classpath(libs.kover.plugin)
        classpath(libs.shot.plugin)

        // NOTE: Do not place your application dependencies here; they belong
        // in the individual module build.gradle files
    }
}

plugins {
    alias(libs.plugins.android.application).apply(false)
    alias(libs.plugins.android.library).apply(false)
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

allprojects {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
    afterEvaluate {
        tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask> {
            exclude("**/generated/**")
        }
        tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintFormatTask> {
            exclude("**/generated/**")
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
