plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.roborazzi) apply false
  alias(libs.plugins.secrets) apply false
  alias(libs.plugins.google.services) apply false
}

/**
 * Aggregates the per-module boundary checks that `stratum.jvm` registers.
 *
 * The real enforcement is the module graph: a pure module never sees the Android
 * classpath, so an `import android.*` fails to compile. This task is the alarm
 * for the way around that -- adding an Android dependency or plugin to a module
 * that is supposed to stay pure.
 */
val pureModules = listOf(":core:domain", ":engine:world", ":content:igbo", ":legacy:domain")

tasks.register("architectureCheck") {
  group = "verification"
  description = "Runs the clean architecture boundary checks across all pure modules."
  dependsOn(pureModules.map { "$it:architectureCheck" })
}
