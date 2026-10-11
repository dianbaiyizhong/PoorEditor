plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

android {
    namespace = "com.zhenmei.android.pooreditor"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    implementation(libs.coil)
    implementation(libs.coil.video)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    // 对宿主 App 透出的公共 API：内容模型（serialization）、上传（coroutines + okhttp）
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.android)
    api(libs.okhttp)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "com.zhenmei.android"
                artifactId = "pooreditor"
                version = "1.0.0"
                from(components["release"])
            }
        }
    }
}
