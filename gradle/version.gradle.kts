// Shared version calculation functions
extra["calculateVersionCode"] = fun(): Int {
    val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()
    val offset: Int = libs.versions.android.versionCode.offset.get().toInt()
    val githubRunNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
    return if (githubRunNumber > 0)
        githubRunNumber + offset
    else
        20 + offset
}

extra["calculateVersionName"] = fun(): String {
    val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()
    val walletSdkVersion: String = libs.versions.algokit.walletSdk.get()
    return walletSdkVersion
}

extra["getGitHash"] = fun(): String {
    val process =
        ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
    return process.inputStream.bufferedReader().use { it.readText() }.trim()
}
