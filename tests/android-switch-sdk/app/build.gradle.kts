plugins { id("com.android.application") }

android {
    namespace = "dev.pocketconsole"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.pocketconsole.sdkprobe"
        minSdk = 33
        targetSdk = 35
        testInstrumentationRunner = "dev.pocketconsole.SwitchSdkSmokeInstrumentation"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs {
            keepDebugSymbols.add("**/*.so")
            // Adrenotools opens its hooks by path in nativeLibraryDir.
            useLegacyPackaging = true
        }
    }
    sourceSets {
        getByName("main") {
            java.srcDir(rootProject.file(".cache/linked-java"))
            java.srcDir(rootProject.file(".cache/sdl-java"))
            jniLibs.srcDir(rootProject.file(".cache/sdk/runtime"))
        }
        getByName("androidTest") {
            assets.srcDir(rootProject.file(".cache/test-assets"))
        }
    }
}
