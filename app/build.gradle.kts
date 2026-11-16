plugins {
    id("com.android.application") version "9.4.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
}
android {
    namespace = "com.wboat.todo"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.wboat.todo"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
}
dependencies {
    implementation("io.github.itoseo:coui:0.0.1-alpha11")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation:1.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
}
