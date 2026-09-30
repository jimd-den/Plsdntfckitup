plugins { id("stratum.jvm") }

dependencies {
  implementation(project(":engine:microvoxel"))
}

/**
 * Generates microvoxel scenes and path-traces them to PNGs, so generator
 * changes can be judged by eye without a device:
 *   ./gradlew :tools:microvoxelpreview:microPreview
 *   ./gradlew :tools:microvoxelpreview:microPreview --args="build/out 1234 fast"
 */
tasks.register<JavaExec>("microPreview") {
  group = "verification"
  description = "Generates microvoxel scenes and ray-traces them to build/microvoxel-preview."
  mainClass.set("com.stratum.tools.microvoxelpreview.MicroPreview")
  classpath = sourceSets["main"].runtimeClasspath
  args = listOf(layout.buildDirectory.dir("microvoxel-preview").get().asFile.absolutePath)
  jvmArgs("-Djava.awt.headless=true")
  maxHeapSize = "3g"
}

/**
 * The geology: a province map and a diorama of every province.
 *   ./gradlew :tools:microvoxelpreview:geoPreview --args="build/geo 20260928 fast rift_valley"
 */
tasks.register<JavaExec>("geoPreview") {
  group = "verification"
  description = "Draws the geological provinces: a map and one diorama each."
  mainClass.set("com.stratum.tools.microvoxelpreview.GeoPreview")
  classpath = sourceSets["main"].runtimeClasspath
  args = listOf(layout.buildDirectory.dir("geo-preview").get().asFile.absolutePath)
  jvmArgs("-Djava.awt.headless=true")
  maxHeapSize = "3g"
}
