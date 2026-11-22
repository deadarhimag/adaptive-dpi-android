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
val githubActions = providers.environmentVariable("GITHUB_ACTIONS").orNull == "true"

fun requiredCiVariable(name: String): String =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotEmpty() }
        ?: throw GradleException("Для подписи debug APK в GitHub Actions требуется $name.")

val ciRun = if (githubActions) {
    requiredCiVariable("GITHUB_RUN_NUMBER").toIntOrNull()?.takeIf { it in 1..2_099_999_998 }
        ?: throw GradleException("Некорректный GITHUB_RUN_NUMBER для versionCode.")
} else null
val buildNumber = if (ciRun != null) {
    val attempt = requiredCiVariable("GITHUB_RUN_ATTEMPT")
    if (!attempt.matches(Regex("[1-9][0-9]*"))) {
        throw GradleException("Некорректный GITHUB_RUN_ATTEMPT.")
    }
    "$ciRun.$attempt"
} else "local"

android {
    namespace = "org.adaptivedpi.android"
    compileSdk = libs.versions.android.compile.get().toInt()

    defaultConfig {
        applicationId = "org.adaptivedpi.android"
        minSdk = libs.versions.android.min.get().toInt()
        targetSdk = libs.versions.android.compile.get().toInt()
        versionCode = 16
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "build_git_revision", gitRevision ?: "unknown")
        resValue("string", "build_git_modified", hasLocalChanges.toString())
        resValue("string", "build_number", buildNumber)
    }

    buildFeatures {
        compose = true
    }

    if (githubActions) {
        val ciDebug = signingConfigs.create("ciDebug") {
            storeFile = file(requiredCiVariable("ANDROID_CI_DEBUG_KEYSTORE_PATH")).also {
                if (!it.isFile) throw GradleException("Файл CI debug keystore недоступен.")
            }
            storeType = "JKS"
            storePassword = requiredCiVariable("ANDROID_CI_DEBUG_STORE_PASSWORD")
            keyAlias = "adaptive-dpi-ci-debug"
            keyPassword = requiredCiVariable("ANDROID_CI_DEBUG_KEY_PASSWORD")
        }
        buildTypes.getByName("debug").signingConfig = ciDebug
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

if (ciRun != null) {
    androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
        // У повторной попытки того же запуска код сохраняется.
        variant.outputs.forEach { it.versionCode.set(2 + ciRun) }
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
