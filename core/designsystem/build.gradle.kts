plugins {
  id("stratum.android.library")
  id("stratum.android.compose")
}

android { namespace = "com.stratum.core.designsystem" }

dependencies {
  api(project(":core:domain"))

  implementation(libs.androidx.core.ktx)
}
