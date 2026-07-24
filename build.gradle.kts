plugins {
    id("com.android.application")
}

android {
    namespace = "com.myreels.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.myreels.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
