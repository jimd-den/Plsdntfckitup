plugins {
  id("stratum.android.library")
  id("stratum.android.compose")
}

android { namespace = "com.stratum.feature.forge" }

dependencies {
  implementation(project(":core:domain"))
  implementation(project(":core:designsystem"))

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.kotlinx.coroutines.android)
}
