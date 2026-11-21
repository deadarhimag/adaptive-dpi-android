plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

fun gitOutput(vararg arguments: String): String = runCatching {
    val result = providers.exec {
        workingDir(rootDir)
        commandLine("git", *arguments)
        isIgnoreExitValue = true
    }
    if (result.result.get().exitValue == 0) result.standardOutput.asText.get().trim() else ""
}.getOrDefault("")

val gitRevision = gitOutput("rev-parse", "HEAD").takeIf { it.matches(Regex("[0-9a-f]{40}")) }
val hasLocalChanges = gitRevision != null &&
    gitOutput("-c", "core.fileMode=false", "status", "--porcelain").isNotEmpty()
val buildNumber = listOf("GITHUB_RUN_NUMBER", "GITHUB_RUN_ATTEMPT")
    .map { providers.environmentVariable(it).orNull }
    .takeIf { parts -> parts.all { it != null && it.matches(Regex("[1-9][0-9]*")) } }
    ?.joinToString(".") ?: "local"

android {
    namespace = "org.adaptivedpi.android"
    compileSdk = libs.versions.android.compile.get().toInt()

    defaultConfig {
        applicationId = "org.adaptivedpi.android"
        minSdk = libs.versions.android.min.get().toInt()
        targetSdk = libs.versions.android.compile.get().toInt()
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "build_git_revision", gitRevision ?: "unknown")
        resValue("string", "build_git_modified", hasLocalChanges.toString())
        resValue("string", "build_number", buildNumber)
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":designsystem"))
    implementation(project(":vpn"))
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.lifecycle.runtime.compose)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.junit)
}
