import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "edu.uestc.checkin"
    compileSdk = 35

    defaultConfig {
        applicationId = "edu.uestc.checkin.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.work:work-runtime:2.10.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
