import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing can come from one complete source only. Prefer an explicit,
// repository-external properties file for local signing. The root key.properties
// fallback is retained for compatibility and is ignored by Git.
val explicitSigningPropertiesPath = providers
    .gradleProperty("RELEASE_SIGNING_PROPERTIES_FILE")
    .orElse(providers.environmentVariable("RELEASE_SIGNING_PROPERTIES_FILE"))
    .orNull
    ?.takeIf { it.isNotBlank() }
val legacySigningPropertiesFile = rootProject.file("key.properties")
if (explicitSigningPropertiesPath != null) {
    check(File(explicitSigningPropertiesPath).isAbsolute) {
        "RELEASE_SIGNING_PROPERTIES_FILE must be an absolute path"
    }
}
val signingPropertiesFile = explicitSigningPropertiesPath?.let(::file)
    ?: legacySigningPropertiesFile.takeIf { it.isFile }

if (explicitSigningPropertiesPath != null) {
    check(checkNotNull(signingPropertiesFile).isFile && signingPropertiesFile.canRead()) {
        "RELEASE_SIGNING_PROPERTIES_FILE does not point to a readable file"
    }
}

val signingPropertyNames = linkedMapOf(
    "RELEASE_STORE_FILE" to "storeFile",
    "RELEASE_STORE_PASSWORD" to "storePassword",
    "RELEASE_KEY_ALIAS" to "keyAlias",
    "RELEASE_KEY_PASSWORD" to "keyPassword",
)

fun directSigningValue(name: String): String? = providers
    .gradleProperty(name)
    .orElse(providers.environmentVariable(name))
    .orNull
    ?.takeIf { it.isNotBlank() }

val directSigningValues = signingPropertyNames.keys.associateWith(::directSigningValue)
val fileSigningValues = signingPropertiesFile?.let { propertiesFile ->
    check(directSigningValues.values.none { it != null }) {
        "Do not combine a release signing properties file with direct signing properties"
    }
    val properties = Properties().apply {
        propertiesFile.inputStream().use(::load)
    }
    signingPropertyNames.mapValues { (_, keyPropertiesName) ->
        properties.getProperty(keyPropertiesName)?.takeIf { it.isNotBlank() }
    }
}

val releaseSigningValues = fileSigningValues ?: directSigningValues
val configuredReleaseSigningValues = releaseSigningValues.filterValues { it != null }
check(configuredReleaseSigningValues.isEmpty() || configuredReleaseSigningValues.size == signingPropertyNames.size) {
    val missing = releaseSigningValues.filterValues { it == null }.keys.joinToString()
    "Incomplete release signing configuration; missing: $missing"
}
val releaseSigningConfigured = configuredReleaseSigningValues.isNotEmpty()
val releaseStoreFile = releaseSigningValues.getValue("RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningValues.getValue("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValues.getValue("RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValues.getValue("RELEASE_KEY_PASSWORD")

// Single source for artifact base name: PixelifyInfinity-<versionName>-<buildType>.apk/.aab
val appVersionCode = 9
val appVersionName = "1.4.0"

android {
    namespace = "io.github.samson910022.pixelifyphotos"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.samson910022.pixelifyphotos"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        ndk {
            // Primary device ABIs + emulator.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fno-exceptions", "-fno-rtti")
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "27.0.12077973"

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(checkNotNull(releaseStoreFile))
                storePassword = checkNotNull(releaseStorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                keyPassword = checkNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Ensure libpixelify_build.so is stored uncompressed/extractable for LSPosed.
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

base {
    archivesName.set("PixelifyInfinity-$appVersionName")
}

val expectedReleaseCertificateSha256 =
    "37186E5C2694E553E5FAB1F7787C04DBCD4384AB84963E60BE9C3CCB6BA907B1"

val verifyReleaseSigningIdentity = tasks.register("verifyReleaseSigningIdentity") {
    group = "verification"
    description = "Fails unless the configured release keystore contains the approved certificate."

    doLast {
        check(releaseSigningConfigured) {
            "Official release publication requires a complete release signing configuration"
        }

        val keyStore = KeyStore.getInstance("PKCS12")
        rootProject.file(checkNotNull(releaseStoreFile)).inputStream().use { input ->
            keyStore.load(input, checkNotNull(releaseStorePassword).toCharArray())
        }
        val certificate = checkNotNull(keyStore.getCertificate(checkNotNull(releaseKeyAlias))) {
            "Configured release key alias was not found in the keystore"
        }
        val actualFingerprint = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }
        check(actualFingerprint == expectedReleaseCertificateSha256) {
            "Configured release certificate fingerprint does not match the approved public certificate"
        }
    }
}

val verifiedRelease = tasks.register("verifiedRelease") {
    group = "distribution"
    description = "Builds APK and AAB artifacts only after validating the fixed release signer."
    dependsOn(verifyReleaseSigningIdentity, "assembleRelease", "bundleRelease")
}

// When verifiedRelease adds the verifier to the graph, every Android release task must
// wait for it. For ordinary unsigned release builds the verifier is absent, so this
// ordering rule has no effect.
tasks.configureEach {
    if (name.contains("Release") &&
        name != "verifyReleaseSigningIdentity" &&
        name != "verifiedRelease"
    ) {
        mustRunAfter(verifyReleaseSigningIdentity)
    }
}

// Fail before task execution rather than spending time on an unsigned publication build.
gradle.taskGraph.whenReady {
    if (allTasks.any { it == verifiedRelease.get() }) {
        check(releaseSigningConfigured) {
            "verifiedRelease requires external release signing configuration"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    compileOnly(libs.libxposed.api)
    // Available at unit-test runtime so DeviceSpoofer/FeatureSpoofer can load.
    testImplementation(libs.libxposed.api)
    implementation(libs.libxposed.service)

    // Unit testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:${libs.versions.kotlin.get()}")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.json:json:20231013")
}
