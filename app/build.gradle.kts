import java.io.FileInputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing credentials are loaded from keystore.properties (gitignored).
// When the file is absent (e.g. a fresh clone), the release build stays unsigned
// instead of failing.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

android {
    namespace = "com.azizjon.notes"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.azizjon.notes"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "2.2"
        vectorDrawables { useSupportLibrary = true }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Installs beside the released app, so a phone keeps its real notes while a build is tested.
            applicationIdSuffix = ".debug"
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            // The speech engine's native code is about 30 MB per CPU type; phones are arm64.
            ndk { abiFilters += "arm64-v8a" }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    testOptions {
        unitTests.all { test ->
            // A longer clash run: gradlew testDebugUnitTest -Pclash.seeds=500 -Pclash.rounds=300
            for (name in listOf("clash.seeds", "clash.rounds")) {
                project.findProperty(name)?.let { test.systemProperty(name, it) }
            }
            test.testLogging { showStandardStreams = true }
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        // The clash harness runs both on the PC (unit tests) and on a device (instrumented tests).
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
    }
    packaging {
        jniLibs {
            // sherpa-onnx's JNI library needs only libonnxruntime.so; its C and C++ API libraries go unused.
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
    }
}

// The sherpa-onnx speech engine ships as an AAR on its GitHub release, not on Maven Central. It is
// downloaded once into app/libs (gitignored) and checked against the SHA-256 the release lists.
val sherpaOnnxVersion: String = libs.versions.sherpaOnnx.get()
val sherpaOnnxSha256 = "4c766e2e74c9d33d595338c21fe3b305c73ba45d47e96a235675c33142a2ec50"
val sherpaOnnxAar: File = file("libs/sherpa-onnx-$sherpaOnnxVersion.aar")
val fetchSherpaOnnx by tasks.registering {
    val target = sherpaOnnxAar
    val expected = sherpaOnnxSha256
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/" +
        "sherpa-onnx-$sherpaOnnxVersion.aar"
    outputs.file(target)
    doLast {
        fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").let { md ->
            f.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    md.update(buffer, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        if (target.exists() && sha256(target) == expected) return@doLast
        target.parentFile.mkdirs()
        val part = File(target.path + ".part")
        URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        val got = sha256(part)
        if (got != expected) {
            part.delete()
            throw GradleException("sherpa-onnx AAR checksum mismatch: got $got, expected $expected")
        }
        if (target.exists()) target.delete()
        check(part.renameTo(target)) { "Could not move $part to $target" }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val copyDebugApkToRoot by tasks.registering {
    dependsOn("packageDebug")
    val debugApk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    val rootApk = rootProject.layout.projectDirectory.file("Notes-debug.apk")
    inputs.file(debugApk)
    outputs.file(rootApk)

    doLast {
        debugApk.get().asFile.copyTo(rootApk.asFile, overwrite = true)
    }
}

afterEvaluate {
    tasks.named("assembleDebug") {
        dependsOn(copyDebugApkToRoot)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.richeditor.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.exifinterface)
    implementation(libs.jgit)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(files(sherpaOnnxAar).builtBy(fetchSherpaOnnx))

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
