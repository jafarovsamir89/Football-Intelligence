import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val privateLocalProperties = Properties().apply {
    val privateConfig = rootProject.file("local.properties")
    if (privateConfig.isFile) privateConfig.inputStream().use { load(it) }
}
val personalOddsKey = privateLocalProperties.getProperty("THE_ODDS_API_KEY").orEmpty().trim()
val moviOddsAgeConfirmedLocally = privateLocalProperties.getProperty("MOVIODDS_AGE_CONFIRMED").toBoolean()
val escapedPersonalOddsKey = personalOddsKey
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "az.simplesoft.footballintelligence"
    compileSdk = 35
    defaultConfig {
        applicationId = "az.simplesoft.footballintelligence"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appLabel"] = "Football Intelligence"
        buildConfigField("String", "THE_ODDS_API_KEY", "\"\"")
        buildConfigField("boolean", "MOVIODDS_AGE_CONFIRMED_LOCALLY", "false")
    }
    buildTypes {
        debug { }
        release { isMinifyEnabled = false }
        create("personal") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".personal"
            versionNameSuffix = "-personal"
            manifestPlaceholders["appLabel"] = "Football Intelligence Personal"
            buildConfigField("String", "THE_ODDS_API_KEY", "\"$escapedPersonalOddsKey\"")
            buildConfigField("boolean", "MOVIODDS_AGE_CONFIRMED_LOCALLY", moviOddsAgeConfirmedLocally.toString())
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.work:work-testing:2.12.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.compose.runtime:runtime-livedata")
}
