plugins {
    id("com.android.application")
}

val releaseStore = providers.environmentVariable("POCKET_ANDROID_KEYSTORE_FILE").orNull
val releasePassword = providers.environmentVariable("POCKET_ANDROID_KEYSTORE_PASSWORD").orNull

val prepareDsCore by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", "scripts/prepare-core.py")
}

val prepareThreeDsCore by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", "scripts/prepare-3ds-jni.py")
}

tasks.configureEach {
    if (name == "preBuild" || name.startsWith("configureNdkBuild") || name.startsWith("buildNdkBuild")) {
        dependsOn(prepareDsCore)
        dependsOn(prepareThreeDsCore)
    }
}

android {
    namespace = "dev.pocketconsole"
    compileSdk = 36
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "dev.pocketconsole"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.3.0-alpha.4"
        testInstrumentationRunner = providers.gradleProperty("smokeRunner")
            .orElse("dev.pocketconsole.DsSmokeInstrumentation").get()
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            ndkBuild {
                arguments += "NDK_APPLICATION_MK=${file("src/main/cpp/Application.mk")}"
            }
        }
    }
    testBuildType = providers.gradleProperty("pocketTestBuildType").orElse("debug").get()
    signingConfigs {
        if (!releaseStore.isNullOrBlank() && !releasePassword.isNullOrBlank()) {
            create("pocketRelease") {
                storeFile = file(releaseStore)
                storePassword = releasePassword
                keyAlias = "pocket-console"
                keyPassword = releasePassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("pocketRelease")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].jniLibs.srcDir(rootProject.file(".cache/3ds-jni"))
    lint {
        abortOnError = true
    }
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }
}
