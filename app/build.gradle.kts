import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 版本号唯一来源：android-app/version.properties（发版脚本与 CI 读同一个文件）。
val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

val ndkVersionPin = "29.0.14206865"
val requiredRuntimeLibs = listOf(
    "libnode.so", "libmagpie.so", "libcrypto.so", "libssl.so", "libz.so",
    "libicuuc.so", "libicui18n.so", "libicudata.so", "libsqlite3.so",
    "libcares.so", "libffi.so", "libc++_shared.so", "libandroid-support.so",
)

android {
    namespace = "cn.luoyi0914.ofmgateway"
    compileSdk = 37
    ndkVersion = ndkVersionPin

    defaultConfig {
        applicationId = "cn.luoyi0914.ofmgateway"
        minSdk = 26
        targetSdk = 37
        versionCode = versionProps.getProperty("versionCode", "1").toInt()
        versionName = versionProps.getProperty("versionName", "0.0.0")
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
            // minSdk 26 不需要 v1（JAR）签名；v2+v3 都给上，v3 才能在将来轮换密钥。
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            // R8 打开：混淆顺带做无用代码消除。JNI 方法名必须保留，规则见 proguard-rules.pro。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // Node 需要从 nativeLibraryDir 直接 exec，必须是未压缩的落盘文件。
        jniLibs.useLegacyPackaging = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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
    val ndkToolchain = "$sdkDir/ndk/$ndkVersionPin/toolchains/llvm/prebuilt/linux-x86_64/bin"
    environment("ANDROID_NDK_HOME", "$sdkDir/ndk/$ndkVersionPin")
    environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", "$ndkToolchain/aarch64-linux-android26-clang")
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

// 运行时库尚未生成（clean clone）时，先从仓库 .cache 还原到 jniLibs。
// 脚本位于 android-app/scripts（随仓库入库），不要写成 app/scripts。
val prepareRuntimeLibs by tasks.registering(Exec::class) {
    group = "build"
    description = "Materializes jniLibs runtime from .cache when missing."
    onlyIf { !file("$projectDir/src/main/jniLibs/arm64-v8a/libnode.so").exists() }
    workingDir = rootProject.projectDir
    // 用 rootProject：脚本在 android-app/scripts，而 $projectDir 是 app/。
    commandLine("bash", "${rootProject.projectDir}/scripts/prepare-runtime-libs.sh")
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
// 清单必须等于实际打包集合：漏一个就会在真机上退化为引擎/SSL 缺失。
val verifyRuntimeLibs by tasks.registering {
    group = "build"
    description = "Asserts every required runtime .so exists in jniLibs."
    doLast {
        val dir = file("$projectDir/src/main/jniLibs/arm64-v8a")
        val missing = requiredRuntimeLibs.filter { !File(dir, it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException("缺少运行时库: $missing（先运行 android-app/scripts/prepare-runtime-libs.sh）")
        }
    }
}

tasks.matching { it.name == "mergeDebugJniLibFolders" || it.name == "mergeReleaseJniLibFolders" }
    .configureEach { dependsOn(prepareRuntimeLibs, syncNative, verifyRuntimeLibs) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
