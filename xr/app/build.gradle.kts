plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.picoxr.capturesdkdemo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.synapath.picopipeline"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.1-pipeline.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // Use the separately supplied SDK, not the older AAR bundled in app/libs.
    val captureAar = providers.environmentVariable("PICO_CAPTURE_AAR").orNull
        ?.let { file(it) } ?: rootProject.file("../third_party/pico/capturelib-0.1.1.aar")
    require(captureAar.isFile) { "Missing PICO SDK AAR: $captureAar" }
    implementation(files(captureAar))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}