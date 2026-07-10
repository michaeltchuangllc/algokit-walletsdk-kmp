pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/ktor/eap")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://maven.pkg.jetbrains.space/kotlin/p/wasm/experimental")
        maven( "https://androidx.dev/storage/compose-compiler/repository")
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/dev")
    }
}

rootProject.name = "algokit-walletsdk-kmp"
include(":androidDemoApp")
include(":sharedDemoApp")
include(":serviceDemoApp")
include(":wallet-sdk-core")
include(":wallet-sdk-ui")
if (file("wallet-sdk-service").isDirectory) {
    include(":wallet-sdk-service")
}
