plugins { id("stratum.jvm") }

dependencies {
  api(project(":core:domain"))
  // Towns are one of the generator's passes, built by the settlement layer.
  api(project(":engine:settlement"))
}
