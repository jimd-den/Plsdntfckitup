plugins {
  id("stratum.android.library")
  id("stratum.android.compose")
}

android { namespace = "com.stratum.feature.forge" }

dependencies {
  implementation(project(":core:domain"))
  implementation(project(":core:designsystem"))
  implementation(project(":agents"))

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.kotlinx.coroutines.android)

  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
  testImplementation(libs.kotlinx.coroutines.test)
  // A real pack to forge against, so the view model is tested on the checks the game runs.
  testImplementation(project(":content:igbo"))
}
