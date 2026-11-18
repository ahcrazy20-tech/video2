import java.util.zip.ZipFile
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val offlineLibs = layout.buildDirectory.dir("generated/offline/jniLibs")
// A local/release operator can use the original signing key without putting it in the repo.
// Unconfigured CI still uses its own debug signing key; that is not an upgrade guarantee.
val signingStore = providers.environmentVariable("VIDEO2_SIGNING_STORE").orNull
fun signingSecret(name: String): String = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
    ?: error("$name is required with VIDEO2_SIGNING_STORE")

android {
    sourceSets.getByName("main").jniLibs.srcDir(offlineLibs)
    namespace = "com.ahcrazy.video2"
    compileSdk = 34
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.ahcrazy.video2"
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    if (signingStore != null) signingConfigs.getByName("debug").apply {
        storeFile = file(signingStore).also { check(it.isFile) { "VIDEO2_SIGNING_STORE is not a file" } }
        storePassword = signingSecret("VIDEO2_SIGNING_STORE_PASSWORD")
        keyAlias = signingSecret("VIDEO2_SIGNING_KEY_ALIAS")
        keyPassword = signingSecret("VIDEO2_SIGNING_KEY_PASSWORD")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            if (System.getenv("GITHUB_ACTIONS") == "true") test.addTestListener(object : TestListener {
                override fun beforeSuite(suite: TestDescriptor) = Unit
                override fun beforeTest(test: TestDescriptor) = Unit
                override fun afterSuite(suite: TestDescriptor, result: TestResult) = Unit
                override fun afterTest(test: TestDescriptor, result: TestResult) {
                    if (result.resultType == TestResult.ResultType.FAILURE) {
                        val detail = "${test.className}.${test.name}: ${result.exception}".take(900)
                            .replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
                        println("::error title=Android test failure::$detail")
                    }
                }
            })
        }
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

val sdkDir = androidComponents.sdkComponents.sdkDirectory
val compileOfflineRuntimes by tasks.registering(Exec::class) {
    val script = rootProject.file("tools/build-offline.sh")
    inputs.file(script)
    inputs.dir(rootProject.file("native"))
    outputs.dir(offlineLibs)
    doFirst {
        commandLine("bash", script, "android", layout.buildDirectory.dir("intermediates/offline").get().asFile,
            offlineLibs.get().asFile, sdkDir.get().asFile)
    }
}
// Order packaging's jniLibs merge explicitly as well as preBuild.
tasks.named("preBuild") { dependsOn(compileOfflineRuntimes) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(compileOfflineRuntimes) }

// The existing APK workflow verifies that the assembled debug APK includes the baseline and accelerated engines.
val verifyOfflineDebugApk by tasks.registering {
    dependsOn("packageDebug")
    doLast {
        val apks = layout.buildDirectory.dir("outputs/apk/debug").get().asFile.listFiles()
            .orEmpty().filter { it.extension == "apk" }
        check(apks.isNotEmpty()) { "No debug APK was produced" }
        for (apk in apks) ZipFile(apk).use { zip ->
            for (engine in listOf("whisper", "whisper_fp16", "whisper_vulkan", "hymt", "hymt_dotprod")) {
                check(zip.getEntry("lib/arm64-v8a/libvideo2_$engine.so") != null) {
                    "${apk.name} is missing the on-device $engine runtime"
                }
            }
            check(zip.entries().asSequence().none { it.name.endsWith(".gguf") ||
                it.name.substringAfterLast('/').let { name -> name.startsWith("ggml-") && name.endsWith(".bin") } }) {
                "Model weights must remain outside the APK"
            }
        }
    }
}
tasks.matching { it.name == "assembleDebug" }.configureEach { dependsOn(verifyOfflineDebugApk) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.hls)
    implementation(libs.androidx.media3.dash)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.coil.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
}
