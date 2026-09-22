plugins { id("stratum.jvm") }

dependencies {
  implementation(project(":core:domain"))
  implementation(project(":engine:world"))
  implementation(project(":engine:render"))
  implementation(project(":content:igbo"))
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
