plugins {
  id("stratum.jvm")
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(libs.kotlinx.coroutines.core)

  // Parsing generated content is domain logic, not transport: a model that
  // returns malformed JSON is a content problem, and the tests for it should
  // not need a network stack or Android.
  implementation(libs.kotlinx.serialization.json)
}
