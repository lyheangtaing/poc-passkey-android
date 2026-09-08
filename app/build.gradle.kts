plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val passkeyRpId = providers.gradleProperty("PASSKEY_RP_ID")
    .orElse("passkey-poc.example.com")

android {
    namespace = "com.lyheang.pocpasskey"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lyheang.pocpasskey"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "PASSKEY_RP_ID", "\"${passkeyRpId.get()}\"")
        manifestPlaceholders["passkeyRpId"] = passkeyRpId.get()
        resValue(
            "string",
            "asset_statements",
            "[{\"include\":\"https://${passkeyRpId.get()}/.well-known/assetlinks.json\"}]"
        )
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
