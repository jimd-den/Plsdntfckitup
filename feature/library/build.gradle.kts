plugins {
  id("stratum.android.library")
  id("stratum.android.compose")
}

android { namespace = "com.stratum.feature.library" }

dependencies {
  implementation(project(":core:domain"))
  implementation(project(":core:designsystem"))

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
  testImplementation(libs.kotlinx.coroutines.test)
}
