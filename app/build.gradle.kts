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

val prepareSwitchCore by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", "scripts/prepare-switch-jni.py")
}

tasks.configureEach {
    if (name == "preBuild" || name.startsWith("configureNdkBuild") || name.startsWith("buildNdkBuild")) {
        dependsOn(prepareDsCore)
        dependsOn(prepareThreeDsCore)
        dependsOn(prepareSwitchCore)
    }
}

android {
    namespace = "dev.pocketconsole"
    compileSdk = 36
    ndkVersion = if (rootProject.file(".cache/switch-probes/pocket-switch2-arm64-v8a").isDirectory)
        "28.2.13676358" else "27.1.12297006"

    defaultConfig {
        applicationId = "dev.pocketconsole"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.3.0-alpha.6"
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
    sourceSets["main"].jniLibs.srcDir(rootProject.file(".cache/switch-jni/runtime"))
    sourceSets["main"].java.srcDir(rootProject.file(".cache/switch-jni/java"))
    packaging {
        jniLibs {
            // The Vulkan loader opens hook libraries from nativeLibraryDir by path.
            useLegacyPackaging = true
        }
    }
    lint {
        abortOnError = true
    }
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }
}
