plugins {
  id("stratum.android.library")
  id("stratum.android.compose")
}

android { namespace = "com.stratum.feature.studio" }

dependencies {
  implementation(project(":legacy:domain"))
  // The studio renders sprite sheets and exports them, which needs the bitmap
  // and file helpers. Those are platform infrastructure rather than a rule, so
  // the dependency is on the data module by design.
  implementation(project(":legacy:data"))

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.kotlinx.coroutines.android)
}
