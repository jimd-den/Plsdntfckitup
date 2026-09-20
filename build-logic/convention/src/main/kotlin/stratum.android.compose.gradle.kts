/**
 * Compose on top of `stratum.android.library`. Keeps the Compose BOM and the
 * baseline UI dependencies in one place so feature modules declare only what is
 * genuinely theirs.
 */
plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  buildFeatures { compose = true }
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
  val bom = platform(libs.findLibrary("androidx-compose-bom").get())
  add("implementation", bom)
  add("androidTestImplementation", bom)
  add("implementation", libs.findLibrary("androidx-compose-ui").get())
  add("implementation", libs.findLibrary("androidx-compose-ui-graphics").get())
  add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())
  add("implementation", libs.findLibrary("androidx-compose-material3").get())
  add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
}
