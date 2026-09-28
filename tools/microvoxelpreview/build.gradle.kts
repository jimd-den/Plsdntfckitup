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
