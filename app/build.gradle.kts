import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val includePayloads = providers.gradleProperty("rufid.includePayloads")
    .map { !it.equals("false", ignoreCase = true) }
    .orElse(true)

android {
    namespace = "io.github.rufid"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.rufid"
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "0.2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "INCLUDE_PAYLOADS", includePayloads.get().toString())
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            excludes += "META-INF/version-control-info.textproto"
        }
        // Extract jniLibs to nativeLibraryDir so PIE tools (mkntfs/stream) are executable files.
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        checkTestSources = false
    }

    sourceSets {
        getByName("main") {
            if (includePayloads.get()) {
                java.srcDir("src/payloads/java")
                assets.srcDir(rootProject.layout.projectDirectory.dir("payloads/out/assets"))
                jniLibs.srcDir(rootProject.layout.projectDirectory.dir("payloads/out/jniLibs"))
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    val sevenZipAar = rootProject.layout.projectDirectory.file("payloads/out/aar/sevenzipjbinding.aar").asFile
    if (includePayloads.get() && sevenZipAar.isFile) {
        implementation(files(sevenZipAar))
    }
}

tasks.register("verifyPayloadStage") {
    onlyIf { includePayloads.get() }
    doLast {
        val payloadOut = rootProject.layout.projectDirectory.dir("payloads/out").asFile
        val assets = payloadOut.resolve("assets")
        val jniLibs = payloadOut.resolve("jniLibs")
        check(assets.exists() || jniLibs.exists()) {
            "Payload packaging is enabled, but payloads/out has no staged assets or native libraries."
        }
        val requiredAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        val requiredWimLibraries = listOf("libwimutils.so", "librufidwim.so")
        val requiredNtfsTools = listOf("librufidmkntfs.so", "librufidntfsstream.so")
        val requiredNativeLibraries = requiredWimLibraries + requiredNtfsTools
        val missingNativeLibraries = requiredAbis.flatMap { abi ->
            requiredNativeLibraries.mapNotNull { library ->
                "jniLibs/$abi/$library".takeUnless { payloadOut.resolve(it).isFile }
            }
        }
        check(missingNativeLibraries.isEmpty()) {
            "Payload packaging is enabled, but native payload tools are incomplete: ${missingNativeLibraries.joinToString()}."
        }
        val shaMismatch = requiredAbis.flatMap { abi ->
            requiredNativeLibraries.mapNotNull { library ->
                val so = payloadOut.resolve("jniLibs/$abi/$library")
                val sidecar = payloadOut.resolve("jniLibs/$abi/$library.sha256")
                when {
                    !sidecar.isFile -> "jniLibs/$abi/$library.sha256 missing"
                    else -> {
                        val expected = sidecar.readText().trim().substringBefore(' ').lowercase()
                        val digest = MessageDigest.getInstance("SHA-256").digest(so.readBytes())
                        val actual = digest.joinToString("") { byte ->
                            "%02x".format(byte)
                        }
                        "jniLibs/$abi/$library sha mismatch".takeUnless { expected == actual }
                    }
                }
            }
        }
        check(shaMismatch.isEmpty()) {
            "Native library SHA-256 sidecar mismatch: ${shaMismatch.joinToString()}"
        }
        check(payloadOut.resolve("jniLibs/ntfs3g.source.txt").isFile) {
            "Payload packaging is enabled, but jniLibs/ntfs3g.source.txt source manifest is missing."
        }
        check(payloadOut.resolve("aar/sevenzipjbinding.aar").isFile) {
            "Payload packaging is enabled, but the 7-Zip-JBinding Android runtime AAR is missing."
        }
        check(payloadOut.resolve("assets/payloads/uefi/uefi-ntfs.img").isFile) {
            "Payload packaging is enabled, but the UEFI:NTFS helper image is missing."
        }
    }
}

tasks.named("preBuild") {
    dependsOn("verifyPayloadStage")
}

tasks.configureEach {
    if (name.startsWith("extract") && name.endsWith("VersionControlInfo")) {
        enabled = false
    }
}
