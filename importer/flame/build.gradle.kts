plugins { id("stratum.jvm") }

dependencies {
  api(project(":importer:common"))
  api(project(":importer:tiled"))
  implementation(libs.kotlinx.serialization.json)
}
