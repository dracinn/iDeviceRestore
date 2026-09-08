import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ciVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
val ciVersionName = System.getenv("VERSION_NAME") ?: "0.1.0-dev"
val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")

val aria2Version = "1.37.0"
val aria2ReleaseName = "aria2-$aria2Version-aarch64-linux-android-build1"
val aria2ReleaseUrl = "https://github.com/aria2/aria2/releases/download/release-$aria2Version/$aria2ReleaseName.zip"
val generatedAria2Lib = layout.buildDirectory.file("generated/aria2c/jniLibs/arm64-v8a/libaria2c.so")

val prepareAria2c by tasks.registering {
    outputs.file(generatedAria2Lib)
    doLast {
        val output = generatedAria2Lib.get().asFile
        if (output.isFile && output.length() > 0L) return@doLast
        output.parentFile.mkdirs()
        val temp = output.resolveSibling(output.name + ".tmp")
        temp.delete()
        URI(aria2ReleaseUrl).toURL().openStream().use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var found = false
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name == "$aria2ReleaseName/aria2c") {
                        temp.outputStream().buffered().use { out -> zip.copyTo(out) }
                        found = true
                        break
                    }
                    zip.closeEntry()
                }
                check(found && temp.isFile && temp.length() > 0L) {
                    "Official aria2c executable was not found in $aria2ReleaseUrl"
                }
            }
        }
        check(temp.renameTo(output)) { "Could not stage official aria2c executable" }
        println("Packaged official aria2c $aria2Version from aria2/aria2")
    }
}

android {
    namespace = "com.idevicerestore.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.idevicerestore.android"
        minSdk = 26
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = ciVersionName
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/aria2c/jniLibs"))

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            if (!releaseKeystorePath.isNullOrBlank()) {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (!releaseKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareAria2c)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
}
