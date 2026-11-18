plugins {
    id("com.android.application")
}

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
        versionCode = 3
        versionName = "0.3.0-3ds-alpha"
        testInstrumentationRunner = "dev.pocketconsole.DsSmokeInstrumentation"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            ndkBuild {
                arguments += "NDK_APPLICATION_MK=${file("src/main/cpp/Application.mk")}"
            }
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
