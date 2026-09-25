plugins { id("stratum.jvm") }

dependencies {
  implementation(project(":core:domain"))
  implementation(project(":engine:world"))
  implementation(project(":engine:render"))
  implementation(project(":engine:scene"))
  implementation(project(":content:igbo"))
  implementation(project(":plugins"))
}

/**
 * Renders the style sheet to PNGs.
 *
 * A look you can only evaluate by installing the app on a phone is a look
 * nobody reviews. This task puts the same frames the game draws into a folder
 * in a couple of seconds, on any machine, with no device and no emulator.
 */
tasks.register<JavaExec>("artPreview") {
  group = "verification"
  description = "Renders the world at every built-in style into build/art-preview."
  mainClass.set("com.stratum.tools.artpreview.ArtPreview")
  classpath = sourceSets["main"].runtimeClasspath
  args = listOf(layout.buildDirectory.dir("art-preview").get().asFile.absolutePath)
}

/**
 * Renders the world in 3D at every built-in style into build/scene-preview,
 * using any forged textures found in build/forge.
 */
tasks.register<JavaExec>("scenePreview") {
  group = "verification"
  description = "Renders the 3D world at every style into build/scene-preview."
  mainClass.set("com.stratum.tools.artpreview.ScenePreview")
  classpath = sourceSets["main"].runtimeClasspath
  args = listOf(
    layout.buildDirectory.dir("scene-preview").get().asFile.absolutePath,
    rootProject.layout.projectDirectory.dir("content/igbo/src/main/resources/forge").asFile.absolutePath,
  )
  maxHeapSize = "3g"
}

/**
 * Generates an asset kit with an image model and writes it into the pack's
 * resources. Needs OPENROUTER_API_KEY in the environment; the key is never
 * written anywhere.
 *
 *   ./gradlew :tools:artpreview:forgeKit --args="house 'stratum house style'"
 */
tasks.register<JavaExec>("forgeKit") {
  group = "generation"
  description = "Forges textures and sprites for a style into content/igbo resources."
  mainClass.set("com.stratum.tools.artpreview.ForgeKit")
  classpath = sourceSets["main"].runtimeClasspath
  workingDir = rootProject.projectDir
  environment("OPENROUTER_API_KEY", System.getenv("OPENROUTER_API_KEY") ?: "")
}

/**
 * Rebuilds a kit's finished files of one kind from the raw originals the forge
 * kept, without calling the model:
 *   ./gradlew :tools:artpreview:refinishKit --args="house GROUND_MAP"
 */
tasks.register<JavaExec>("refinishKit") {
  group = "generation"
  description = "Re-runs forge post-processing on kept originals."
  mainClass.set("com.stratum.tools.artpreview.RefinishKit")
  classpath = sourceSets["main"].runtimeClasspath
  workingDir = rootProject.projectDir
  maxHeapSize = "2g"
}

/**
 * Imports a Flame game or Tiled project and renders its level, so an importer
 * change can be judged on a real project without a device:
 *   ./gradlew :tools:artpreview:importPreview --args="path/to/game build/import-preview [map]"
 */
tasks.register<JavaExec>("importPreview") {
  group = "verification"
  description = "Imports a Flame or Tiled project, writes its art and renders its level."
  mainClass.set("com.stratum.tools.artpreview.ImportPreview")
  classpath = sourceSets["main"].runtimeClasspath
  workingDir = rootProject.projectDir
  maxHeapSize = "2g"
}
