plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.teleprompter"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.teleprompter"
        minSdk = 26       // 悬浮窗 TYPE_APPLICATION_OVERLAY 需要 API 26+
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")

    // WebSocket 客户端(豆包 ASR)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 协程:用于音频/网络的异步处理
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Spring 动画:平滑滚动
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")

    // 拼音转换:文本对齐算法用(中文同音字容错)
    implementation("com.belerweb:pinyin4j:2.5.1")

    // CameraX:摄像头预览 + 视频录制
    val camerax = "1.3.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}
