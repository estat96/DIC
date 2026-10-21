plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.dic.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.dic.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild { cmake { cppFlags += listOf("-O3", "-std=c++17") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    buildTypes {
        // debug-подписанный release: устанавливается сразу, но с полной оптимизацией
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    val cx = "1.4.1"
    implementation("androidx.camera:camera-core:$cx")
    implementation("androidx.camera:camera-camera2:$cx")
    implementation("androidx.camera:camera-lifecycle:$cx")
    implementation("androidx.camera:camera-view:$cx")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
}
