import java.util.Properties

plugins {
    id("com.android.application")
}

// Watch half of Tare Notice. Same applicationId and (debug) signing key as :app, which the
// Wearable Data Layer requires for the watch to message the phone app.
// Release signing. The key and its passwords live outside the repo: ~/.tare/signing.properties
// (storeFile, storePassword, keyAlias, keyPassword), or the file named by TARE_SIGNING.
// Without it, release builds come out unsigned. Phone and watch must use the same key.
val releaseSigning: Properties? = file(System.getenv("TARE_SIGNING") ?: "${System.getProperty("user.home")}/.tare/signing.properties")
    .takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "io.github.bmp02050.tare"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.bmp02050.tare"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        releaseSigning?.let { s ->
            create("release") {
                storeFile = file(s.getProperty("storeFile"))
                storePassword = s.getProperty("storePassword")
                keyAlias = s.getProperty("keyAlias")
                keyPassword = s.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation("androidx.health:health-services-client:1.1.0")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    // Health Services returns ListenableFuture but only ships Guava at runtime; same version it uses.
    implementation("com.google.guava:guava:32.0.1-android")
}
