plugins { id("stratum.jvm") }

// Plays a microvoxel world on the block engine: the generator the session
// builds when a recipe names `stratum:microvoxel`. Pure Kotlin.
dependencies {
  api(project(":core:domain"))
  api(project(":engine:microvoxel"))
}
