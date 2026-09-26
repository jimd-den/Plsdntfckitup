plugins {
  id("stratum.jvm")
}

dependencies {
  api(project(":core:domain"))
  // Fragments are plugin JSON: what an agent writes is exactly what a person would.
  implementation(project(":plugins"))
  implementation(libs.kotlinx.serialization.json)
}

dependencies {
  testImplementation(project(":content:igbo"))
}
