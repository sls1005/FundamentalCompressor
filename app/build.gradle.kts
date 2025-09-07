import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutlibraries.plugin)
}

android {
    namespace = "test.sls1005.projects.fundamentalcompressor"
    compileSdk = 36
    defaultConfig {
        applicationId = "test.sls1005.projects.fundamentalcompressor"
        minSdk = 24
        targetSdk = 36
        versionCode = 9
        versionName = "0.3.2"
        multiDexEnabled = true
    }
    signingConfigs {
        register("release") {
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin {
        target {
            compilerOptions {
                jvmTarget = JvmTarget.JVM_11
            }
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
    implementation(libs.aboutlibraries)
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.documentfile)
    //noinspection UseTomlInstead
    implementation("androidx.compose.material3:material3:1.4.0-beta03")
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.dec)
    //noinspection UseTomlInstead
    implementation("com.github.luben:zstd-jni:1.5.7-4@aar") // Don't use the version catalog, or it is an error. Use string here.
}

aboutLibraries {
    collect {
        configPath = file("../config")
    }
}

tasks.register<Copy>("Include license") {
    include("LICENSE")
    from("..")
    into("src/main/assets/")
}.also {
    val task = it.get()
    afterEvaluate {
        tasks.named("preReleaseBuild") {
            dependsOn(task)
        }
    }
}
