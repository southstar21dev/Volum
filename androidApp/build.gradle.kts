import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// The release workflow writes the version semantic-release chose into gradle.properties.
// 1.2.3 -> 1020399, 1.2.3-rc.4 -> 1020304, so every release candidate installs before its release.
// Same rule as iosApp/project.yml.
val appVersionName: String = providers.gradleProperty("volum.versionName").get()
val appVersionCode: Int = run {
    val match = requireNotNull(Regex("""(\d+)\.(\d+)\.(\d+)(?:-rc\.(\d+))?""").matchEntire(appVersionName)) {
        "Unsupported version: $appVersionName"
    }
    val (major, minor, patch) = match.groupValues.drop(1).take(3).map(String::toInt)
    val rc = match.groupValues[4].toIntOrNull()
    require(minor < 100 && patch < 100 && (rc ?: 0) < 99) { "Version doesn't fit in versionCode: $appVersionName" }
    (major * 10_000 + minor * 100 + patch) * 100 + (rc ?: 99)
}

// The release key: keystore.properties locally, environment variables in the release workflow.
// Without either, release builds are signed with the debug key (fine for your own phone).
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

fun signingValue(property: String, environmentVariable: String): String? =
    keystoreProperties.getProperty(property) ?: providers.environmentVariable(environmentVariable).orNull

val releaseKeystore: String? = signingValue("storeFile", "VOLUM_KEYSTORE_PATH")

android {
    namespace = "dev.southstar21.volum.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = signingValue("storePassword", "VOLUM_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "VOLUM_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "VOLUM_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "dev.southstar21.volum"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField(
            "String",
            "POCKETBASE_URL",
            "\"${providers.gradleProperty("volum.pocketbaseUrl").getOrElse("https://apivolum.southstar21.dev")}\"",
        )
    }

    buildTypes {
        release {
            // Without a release key, sign with the debug key so the optimised build still installs.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(projects.shared)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.compose.ui)
    // Renders the @Preview composables in shared's commonMain.
    debugImplementation(libs.compose.ui.tooling)
}
