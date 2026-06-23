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
        versionCode = 2
        versionName = "0.1.1"
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
    }

    lint {
        checkTestSources = false
    }

    sourceSets {
        getByName("main") {
            if (includePayloads.get()) {
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
