import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Shared Lync backend: backend.properties (committed) gives every build — Android and iOS,
// whoever builds it — the same database, so Lync codes scan across platforms.
// Only the *publishable* key goes there (it is meant to ship in apps; data is protected by
// row-level security). local.properties can override it with another project.
// With no keys at all the app runs on built-in demo data.
val backendProps = Properties().apply {
    val f = rootProject.file("backend.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val localProps = Properties(backendProps).apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.lync.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lync.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "SUPABASE_URL", "\"${localProps.getProperty("supabase.url", "")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${localProps.getProperty("supabase.anonKey", "")}\"")
        // Force the offline demo even with keys: `lync.demo=true` in local.properties or `-Plync.demo=true`
        val forceDemo = (project.findProperty("lync.demo") ?: localProps.getProperty("lync.demo", "false")).toString().toBoolean()
        buildConfigField("boolean", "FORCE_DEMO", forceDemo.toString())
    }

    androidResources {
        // Per-app language (Settings > Apps > Language) — see res/xml/locales_config.xml
        generateLocaleConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        // Indoor navigation tests (com.hackdecouverte) read the MB floor plans from the classpath
        getByName("test") {
            resources.srcDir("src/main/assets")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.auth)
    implementation(libs.supabase.storage)
    implementation(libs.supabase.realtime)
    implementation(libs.ktor.client.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.maplibre)
    implementation(libs.play.location)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.kotlinx.coroutines.test)
}
