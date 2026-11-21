plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.adaptivedpi.android.vpn"
    compileSdk = libs.versions.android.compile.get().toInt()
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = libs.versions.android.min.get().toInt()
        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        ndkBuild { path = file("src/main/hev/Android.mk") }
    }
    sourceSets["main"].assets.srcDir(rootProject.file(".native/assets"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":engine"))
    api(libs.coroutines.core)
    testImplementation(libs.junit)
}
