plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.drawenginelab.notefile"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Ink file format = Ink's own StrokeInputBatch serialization (gzip protobuf)
    api(libs.androidx.ink.strokes)
    api(libs.androidx.ink.brush)
    implementation(libs.androidx.ink.storage)
    testImplementation(libs.junit)
}
