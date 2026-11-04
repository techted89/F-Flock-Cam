plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val mapsApiKey: String = (project.findProperty("MAPS_API_KEY") as String?)
    ?: System.getenv("MAPS_API_KEY")
    ?: ""
val serverBaseUrl: String = (project.findProperty("SERVER_BASE_URL") as String?)
    ?: System.getenv("SERVER_BASE_URL")
    ?: "http://10.0.2.2:8080/"
val googleWebClientId: String = (project.findProperty("GOOGLE_WEB_CLIENT_ID") as String?)
    ?: System.getenv("GOOGLE_WEB_CLIENT_ID")
    ?: ""
val admobAppId: String = (project.findProperty("ADMOB_APP_ID") as String?)
    ?: System.getenv("ADMOB_APP_ID")
    ?: "ca-app-pub-3940256099942544~3347511713"
val admobRewardedUnitId: String = (project.findProperty("ADMOB_REWARDED_UNIT_ID") as String?)
    ?: System.getenv("ADMOB_REWARDED_UNIT_ID")
    ?: "ca-app-pub-3940256099942544/5224354917"

android {
    namespace = "com.techted89.flockcam"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.techted89.flockcam"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        manifestPlaceholders["ADMOB_APP_ID"] = admobAppId

        buildConfigField("String", "SERVER_BASE_URL", "\"${serverBaseUrl.trimEnd('/')}/\"")
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
        buildConfigField("String", "ADMOB_REWARDED_UNIT_ID", "\"$admobRewardedUnitId\"")
        buildConfigField("boolean", "ENABLE_REMOTE_ACTION_RUNNER", "true")
    }

    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("KEYSTORE_FILE")?.let { file(it) }
            if (keystoreFile != null) {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.bom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.play.services.location)
    implementation(libs.play.services.maps)
    implementation(libs.maps.compose)
    implementation(libs.play.services.ads)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.billing.ktx)

    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.converter)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso)
}
