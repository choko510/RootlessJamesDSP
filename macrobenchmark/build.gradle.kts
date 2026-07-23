plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "me.timschneeberger.rootlessjamesdsp.macrobenchmark"
    compileSdk = AndroidConfig.compileSdk

    defaultConfig {
        minSdk = 26
        targetSdk = AndroidConfig.targetSdk
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    // Mirror the application dimensions so a generated profile can be packaged by every
    // rootless/full, root/full, root/fdroid, and plugin variant.
    flavorDimensions += listOf("version", "dependencies")
    productFlavors {
        create("fdroid") { dimension = "dependencies" }
        create("full") { dimension = "dependencies" }
        create("rootless") { dimension = "version" }
        create("root") { dimension = "version" }
        create("plugin") { dimension = "version" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.3")
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
