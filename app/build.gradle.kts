plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.zhenmei.android.pooreditor.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zhenmei.android.pooreditor.demo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":pooreditor"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.material)
    implementation(libs.coil)
}
