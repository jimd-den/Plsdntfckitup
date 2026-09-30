plugins { id("stratum.jvm") }

dependencies {
  api(project(":engine:scene"))

  // The glTF JSON chunk is read as a tree rather than into generated classes:
  // the format is wide and mostly optional, and only a few fields matter here.
  implementation(libs.kotlinx.serialization.json)
}
