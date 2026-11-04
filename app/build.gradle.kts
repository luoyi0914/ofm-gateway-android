import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cn.luoyi0914.ofmgateway"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "cn.luoyi0914.ofmgateway"
        minSdk = 26
        targetSdk = 37
        versionCode = 15
        versionName = "0.8.3-dev"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            // 凭据只从环境变量或 local.properties 读取，代码里不留任何默认口令。
            val props = Properties()
            val lf = rootProject.file("local.properties")
            if (lf.isFile) lf.inputStream().use { props.load(it) }
            fun secret(env: String, key: String): String? =
                System.getenv(env) ?: props.getProperty(key)
            val storePath = props.getProperty("ofm.storeFile") ?: "keystore/ofm-release.jks"
            storeFile = rootProject.file(storePath)
            storePassword = secret("OFM_STORE_PASSWORD", "ofm.storePassword")
            keyAlias = secret("OFM_KEY_ALIAS", "ofm.keyAlias") ?: "ofm"
            keyPassword = secret("OFM_KEY_PASSWORD", "ofm.keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

val cargoManifest = file("${rootProject.projectDir}/native-gateway/Cargo.toml")
val cargoOut = file("${rootProject.projectDir}/native-gateway/target/aarch64-linux-android/release/libofm_gateway.so")

// Resolve NDK the same way AGP does: local.properties sdk.dir, then ANDROID_HOME.
val sdkDir: String by lazy {
    val props = Properties()
    val f = rootProject.file("local.properties")
    if (f.isFile) { f.inputStream().use { stream -> props.load(stream) } }
    props.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: throw GradleException("Set sdk.dir in android-app/local.properties or ANDROID_HOME")
}

val cargoBin: String = System.getenv("CARGO")?.takeIf { it.isNotEmpty() }
    ?: listOf(
        "${System.getProperty("user.home")}/.cargo/bin/cargo",
        "/root/.cargo/bin/cargo",
        "/usr/local/cargo/bin/cargo",
    ).firstOrNull { File(it).isFile }
    ?: "cargo"

val buildNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Rust JNI supervisor for arm64-v8a via cargo + NDK."
    workingDir = file("${rootProject.projectDir}/native-gateway")
    val ndkToolchain = "$sdkDir/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64/bin"
    environment("ANDROID_NDK_HOME", "$sdkDir/ndk/29.0.14206865")
    environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", "$ndkToolchain/aarch64-linux-android24-clang")
    environment(
        "CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS",
        "-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384",
    )
    commandLine(
        cargoBin, "build",
        "--manifest-path", cargoManifest.absolutePath,
        "--target", "aarch64-linux-android",
        "--release",
    )
    inputs.files(fileTree("${rootProject.projectDir}/native-gateway/src"))
    inputs.file(cargoManifest)
    file("${rootProject.projectDir}/native-gateway/Cargo.lock").takeIf { it.isFile }?.let { inputs.file(it) }
    outputs.file(cargoOut)
}

// 若运行时库尚未生成（clean clone），先跑 prepare-runtime-libs.sh 从仓库 .cache 还原。
val prepareRuntimeLibs by tasks.registering(Exec::class) {
    group = "build"
    description = "Materializes jniLibs runtime from .cache when missing."
    onlyIf { !file("$projectDir/src/main/jniLibs/arm64-v8a/libnode.so").exists() }
    workingDir = rootProject.projectDir
    commandLine("bash", "$projectDir/scripts/prepare-runtime-libs.sh")
}

val syncNative by tasks.registering(Copy::class) {
    group = "build"
    description = "Copies the built Rust library into jniLibs."
    dependsOn(buildNative)
    from(cargoOut)
    into(file("$projectDir/src/main/jniLibs/arm64-v8a"))
    rename { "libofm_gateway.so" }
}

// 打包前校验运行时库齐全，避免 clean clone 打出「无运行时」的包。
val verifyRuntimeLibs by tasks.registering {
    group = "build"
    description = "Asserts required runtime .so files exist in jniLibs."
    doLast {
        val dir = file("$projectDir/src/main/jniLibs/arm64-v8a")
        val required = listOf("libnode.so", "libmagpie.so", "libcrypto.so", "libssl.so", "libz.so", "libicuuc.so", "libicui18n.so", "libsqlite3.so")
        val missing = required.filter { !File(dir, it).isFile }
        if (missing.isNotEmpty()) throw GradleException("缺少运行时库: $missing（先运行 android-app/scripts/prepare-runtime-libs.sh）")
    }
}

tasks.matching { it.name == "mergeDebugJniLibFolders" || it.name == "mergeReleaseJniLibFolders" }.configureEach {
    dependsOn(syncNative, prepareRuntimeLibs, verifyRuntimeLibs)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
