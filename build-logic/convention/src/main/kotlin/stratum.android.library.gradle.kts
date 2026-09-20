/**
 * Convention for Android library modules (adapters, design system, features).
 *
 * AGP 9 applies Kotlin support automatically, so no separate Kotlin plugin.
 */
plugins {
  id("com.android.library")
}

android {
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    minSdk = 24
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
  add("testImplementation", libs.findLibrary("junit").get())
  add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
}
