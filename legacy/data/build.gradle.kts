plugins {
  id("stratum.android.library")
  alias(libs.plugins.google.devtools.ksp)
}

android { namespace = "com.stratum.legacy.data" }

dependencies {
  api(project(":legacy:domain"))

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.logging.interceptor)
  implementation(libs.retrofit)
  implementation(libs.converter.moshi)

  ksp(libs.androidx.room.compiler)
  ksp(libs.moshi.kotlin.codegen)

  // The sprite police runs against real bitmaps, so its tests need a platform.
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
}
