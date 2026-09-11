import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersionName = providers.gradleProperty("ideviceRestoreVersionName").orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: error("ideviceRestoreVersionName must be set in android/gradle.properties")
val semanticVersion = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(appVersionName)
    ?: error("ideviceRestoreVersionName must use x.y.z semantic versioning: $appVersionName")
val versionMajor = semanticVersion.groupValues[1].toInt()
val versionMinor = semanticVersion.groupValues[2].toInt()
val versionPatch = semanticVersion.groupValues[3].toInt()
check(versionMinor in 0..999 && versionPatch in 0..999) {
    "Semantic version minor/patch components must be between 0 and 999: $appVersionName"
}
val appVersionCodeLong = versionMajor.toLong() * 1_000_000L + versionMinor.toLong() * 1_000L + versionPatch
check(appVersionCodeLong in 1..2_100_000_000L) {
    "Derived Android versionCode is outside the supported range: $appVersionCodeLong"
}
val appVersionCode = appVersionCodeLong.toInt()
val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")

val aria2Version = "1.37.0"
val aria2ReleaseName = "aria2-$aria2Version-aarch64-linux-android-build1"
val aria2ReleaseUrl = "https://github.com/aria2/aria2/releases/download/release-$aria2Version/$aria2ReleaseName.zip"
val aria2ExecutableSha256 = "9397aac0de54c8c15b8166486eb80bfe27937bd6d6b6af4bb8383b155213bec1"
val generatedAria2Lib = layout.buildDirectory.file("generated/aria2c/jniLibs/arm64-v8a/libaria2c.so")

fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val prepareAria2c by tasks.registering {
    inputs.property("aria2Version", aria2Version)
    inputs.property("aria2ExecutableSha256", aria2ExecutableSha256)
    outputs.file(generatedAria2Lib)
    doLast {
        val output = generatedAria2Lib.get().asFile
        if (output.isFile && output.sha256() == aria2ExecutableSha256) return@doLast
        output.delete()
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
        val actualSha256 = temp.sha256()
        check(actualSha256 == aria2ExecutableSha256) {
            "aria2c SHA-256 mismatch: expected $aria2ExecutableSha256, got $actualSha256"
        }
        check(temp.renameTo(output)) { "Could not stage official aria2c executable" }
        println("Packaged verified official aria2c $aria2Version sha256=$actualSha256")
    }
}

android {
    namespace = "com.idevicerestore.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.idevicerestore.android"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        manifestPlaceholders["appLabel"] = "iDeviceRestore"
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
