plugins {
  id("stratum.android.library")
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.kotlin.serialization)
}

android { namespace = "com.stratum.core.data" }

dependencies {
  api(project(":core:domain"))

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.logging.interceptor)

  ksp(libs.androidx.room.compiler)
  ksp(libs.moshi.kotlin.codegen)

  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}
