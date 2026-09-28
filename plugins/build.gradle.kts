plugins {
  id("stratum.jvm")
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(project(":importer:common"))
  api(project(":importer:tiled"))
  api(project(":importer:flame"))
  implementation(libs.kotlinx.serialization.json)
}

dependencies {
  testImplementation(project(":content:igbo"))
  // To check a terrain section written as data builds a microvoxel world.
  testImplementation(project(":engine:microbridge"))
}
