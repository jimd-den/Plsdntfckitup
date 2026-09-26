plugins { id("stratum.jvm") }

dependencies {
  api(project(":importer:common"))
  implementation(libs.kotlinx.serialization.json)
}
