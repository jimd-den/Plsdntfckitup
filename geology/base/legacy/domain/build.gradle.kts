plugins {
  id("stratum.jvm")
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
}
