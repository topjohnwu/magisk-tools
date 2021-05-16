import org.apache.tools.ant.filters.FixCrLfFilter
import java.io.PrintStream

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.parcelize")
    kotlin("kapt")
    id("androidx.navigation.safeargs.kotlin")
}

kapt {
    correctErrorTypes = true
    useBuildCache = true
    mapDiagnosticLocations = true
    javacOptions {
        option("-Xmaxerrs", 1000)
    }
    arguments {
        arg("room.incremental", "true")
    }
}

android {
    defaultConfig {
        applicationId = "com.topjohnwu.magisk"
        vectorDrawables.useSupportLibrary = true
        versionName = Config.version
        versionCode = Config.versionCode
        ndk.abiFilters("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        dataBinding = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packagingOptions {
        exclude("/META-INF/*")
        exclude("/org/bouncycastle/**")
        exclude("/kotlin/**")
        exclude("/kotlinx/**")
        exclude("/okhttp3/**")
        exclude("/*.txt")
        exclude("/*.bin")
        doNotStrip("**/*.so")
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

val syncLibs by tasks.registering(Sync::class) {
    into("src/main/jniLibs")
    into("armeabi-v7a") {
        from(rootProject.file("native/out/armeabi-v7a")) {
            include("busybox", "magiskboot", "magiskinit", "magisk")
            rename { if (it == "magisk") "libmagisk32.so" else "lib$it.so" }
        }
    }
    into("x86") {
        from(rootProject.file("native/out/x86")) {
            include("busybox", "magiskboot", "magiskinit", "magisk")
            rename { if (it == "magisk") "libmagisk32.so" else "lib$it.so" }
        }
    }
    into("arm64-v8a") {
        from(rootProject.file("native/out/arm64-v8a")) {
            include("busybox", "magiskboot", "magiskinit", "magisk")
            rename { if (it == "magisk") "libmagisk64.so" else "lib$it.so" }
        }
    }
    into("x86_64") {
        from(rootProject.file("native/out/x86_64")) {
            include("busybox", "magiskboot", "magiskinit", "magisk")
            rename { if (it == "magisk") "libmagisk64.so" else "lib$it.so" }
        }
    }
    onlyIf {
        if (inputs.sourceFiles.files.size != 16)
            throw StopExecutionException("Please build binaries first! (./build.py binary)")
        true
    }
}

val syncAssets by tasks.registering(Sync::class) {
    dependsOn(syncLibs)
    inputs.property("version", Config.version)
    inputs.property("versionCode", Config.versionCode)
    into("src/main/assets")
    from(rootProject.file("scripts")) {
        include("util_functions.sh", "boot_patch.sh", "uninstaller.sh", "addon.d.sh")
    }
    into("chromeos") {
        from(rootProject.file("tools/futility"))
        from(rootProject.file("tools/keys")) {
            include("kernel_data_key.vbprivk", "kernel.keyblock")
        }
    }
    filesMatching("**/util_functions.sh") {
        filter {
            it.replace("#MAGISK_VERSION_STUB",
                "MAGISK_VER='${Config.version}'\n" +
                "MAGISK_VER_CODE=${Config.versionCode}"
            )
        }
        filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"))
    }
}

val syncResources by tasks.registering(Sync::class) {
    dependsOn(syncAssets)
    into("src/main/resources/META-INF/com/google/android")
    from(rootProject.file("scripts/update_binary.sh")) {
        rename { "update-binary" }
    }
    from(rootProject.file("scripts/flash_script.sh")) {
        rename { "updater-script" }
    }
}

tasks["preBuild"]?.dependsOn(syncResources)

android.applicationVariants.all {
    val keysDir = rootProject.file("tools/keys")
    val outSrcDir = File(buildDir, "generated/source/keydata/$name")
    val outSrc = File(outSrcDir, "com/topjohnwu/signing/KeyData.java")

    fun PrintStream.newField(name: String, file: File) {
        println("public static byte[] $name() {")
        print("byte[] buf = {")
        val bytes = file.readBytes()
        print(bytes.joinToString(",") { "(byte)(${it.toInt() and 0xff})" })
        println("};")
        println("return buf;")
        println("}")
    }

    val genSrcTask = tasks.register("generate${name.capitalize()}KeyData") {
        inputs.dir(keysDir)
        outputs.file(outSrc)
        doLast {
            outSrc.parentFile.mkdirs()
            PrintStream(outSrc).use {
                it.println("package com.topjohnwu.signing;")
                it.println("public final class KeyData {")

                it.newField("testCert", File(keysDir, "testkey.x509.pem"))
                it.newField("testKey", File(keysDir, "testkey.pk8"))
                it.newField("verityCert", File(keysDir, "verity.x509.pem"))
                it.newField("verityKey", File(keysDir, "verity.pk8"))

                it.println("}")
            }
        }
    }
    registerJavaGeneratingTask(genSrcTask.get(), outSrcDir)
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(kotlin("stdlib"))
    // Some dependencies request JDK 8 stdlib, specify manually here to prevent version mismatch
    implementation(kotlin("stdlib-jdk8"))
    implementation(project(":app:shared"))

    implementation("com.github.topjohnwu:jtar:1.0.0")
    implementation("com.github.topjohnwu:indeterminate-checkbox:1.0.7")
    implementation("com.github.topjohnwu:lz4-java:1.7.1")
    implementation("com.jakewharton.timber:timber:4.7.1")

    val vBC = "1.68"
    implementation("org.bouncycastle:bcprov-jdk15on:${vBC}")
    implementation("org.bouncycastle:bcpkix-jdk15on:${vBC}")

    val vBAdapt = "4.0.0"
    val bindingAdapter = "me.tatarka.bindingcollectionadapter2:bindingcollectionadapter"
    implementation("${bindingAdapter}:${vBAdapt}")
    implementation("${bindingAdapter}-recyclerview:${vBAdapt}")

    val vMarkwon = "4.6.2"
    implementation("io.noties.markwon:core:${vMarkwon}")
    implementation("io.noties.markwon:html:${vMarkwon}")
    implementation("io.noties.markwon:image:${vMarkwon}")
    implementation("com.caverock:androidsvg:1.4")

    val vLibsu = "3.1.2"
    implementation("com.github.topjohnwu.libsu:core:${vLibsu}")
    implementation("com.github.topjohnwu.libsu:io:${vLibsu}")

    val vRetrofit = "2.9.0"
    implementation("com.squareup.retrofit2:retrofit:${vRetrofit}")
    implementation("com.squareup.retrofit2:converter-moshi:${vRetrofit}")
    implementation("com.squareup.retrofit2:converter-scalars:${vRetrofit}")

    val vOkHttp = "4.9.1"
    implementation("com.squareup.okhttp3:okhttp:${vOkHttp}")
    implementation("com.squareup.okhttp3:logging-interceptor:${vOkHttp}")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:${vOkHttp}")

    val vMoshi = "1.12.0"
    implementation("com.squareup.moshi:moshi:${vMoshi}")
    kapt("com.squareup.moshi:moshi-kotlin-codegen:${vMoshi}")

    val vRoom = "2.3.0"
    implementation("androidx.room:room-runtime:${vRoom}")
    implementation("androidx.room:room-ktx:${vRoom}")
    kapt("androidx.room:room-compiler:${vRoom}")

    val vNav: String by rootProject.extra
    implementation("androidx.navigation:navigation-fragment-ktx:${vNav}")
    implementation("androidx.navigation:navigation-ui-ktx:${vNav}")

    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.0.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.browser:browser:1.3.0")
    implementation("androidx.preference:preference:1.1.1")
    implementation("androidx.recyclerview:recyclerview:1.2.0")
    implementation("androidx.fragment:fragment-ktx:1.3.3")
    implementation("androidx.work:work-runtime-ktx:2.5.0")
    implementation("androidx.transition:transition:1.4.1")
    implementation("androidx.core:core-ktx:1.3.2")
    implementation("com.google.android.material:material:1.3.0")

    implementation("dev.rikka.rikkax.widget:borderview:1.0.1")
}
