import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import org.gradle.api.GradleException
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningPropertiesFile = rootProject.file("release-signing.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.exists()) {
        releaseSigningPropertiesFile.inputStream().use { load(it) }
    }
}
fun releaseSigningProperty(name: String): String? =
    releaseSigningProperties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
val releaseKeystoreFile = releaseSigningProperty("storeFile")?.let { rootProject.file(it) }
val hasPersonalReleaseSigning =
    releaseKeystoreFile?.exists() == true &&
        releaseSigningProperty("storePassword") != null &&
        releaseSigningProperty("keyAlias") != null &&
        releaseSigningProperty("keyPassword") != null
val requestedSignedApkBuild = gradle.startParameter.taskNames.any { taskName ->
    val normalized = taskName.substringAfterLast(":").lowercase()
    normalized == "assemble" ||
        normalized.startsWith("assemble") ||
        normalized.startsWith("install")
}
if (requestedSignedApkBuild && !hasPersonalReleaseSigning) {
    throw GradleException(
        "Missing Android signing config. Run `bash android/scripts/create-release-keystore.sh` first.",
    )
}
val gitCommitShort =
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
    }.standardOutput.asText.get().trim().ifBlank { "unknown" }
val appVersionName = "0.2.0"

android {
    namespace = "com.haochen.codexremote"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.haochen.codexremote"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = appVersionName
    }

    signingConfigs {
        if (hasPersonalReleaseSigning) {
            create("personal") {
                storeFile = checkNotNull(releaseKeystoreFile)
                storePassword = checkNotNull(releaseSigningProperty("storePassword"))
                keyAlias = checkNotNull(releaseSigningProperty("keyAlias"))
                keyPassword = checkNotNull(releaseSigningProperty("keyPassword"))
            }
        }
    }

    buildTypes {
        debug {
            if (hasPersonalReleaseSigning) {
                signingConfig = signingConfigs.getByName("personal")
            }
        }
        release {
            if (hasPersonalReleaseSigning) {
                signingConfig = signingConfigs.getByName("personal")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("String", "GIT_COMMIT_SHORT", "\"$gitCommitShort\"")
    }
}

android.applicationVariants.all variant@{
    outputs.all {
        (this as BaseVariantOutputImpl).outputFileName =
            "codex-remote-control-v$appVersionName-${this@variant.name}.apk"
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
